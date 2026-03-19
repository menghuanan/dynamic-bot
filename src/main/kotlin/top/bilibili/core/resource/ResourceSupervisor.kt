package top.bilibili.core.resource

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

enum class ShutdownPhase {
    INGRESS,
    WORKERS,
    CHANNELS,
    DEPENDENCIES,
    ROOT_SCOPE,
}

interface ResourcePartition {
    val id: String
    val owns: List<String>
    val strictness: ResourceStrictness
    val shutdownPhase: ShutdownPhase

    suspend fun stop()

    fun health(): PartitionHealth = PartitionHealth(id = id, healthy = true, detail = "ok")
}

data class PartitionHealth(
    val id: String,
    val healthy: Boolean,
    val detail: String,
)

data class PartitionFailure(
    val partitionId: String,
    val error: String,
)

data class ResourceStopReport(
    val success: Boolean,
    val totalPartitions: Int,
    val stoppedPartitions: Int,
    val failedPartitions: Int,
    val failures: List<PartitionFailure>,
    val phaseReports: List<PhaseStopReport> = emptyList(),
)

data class PhaseStopReport(
    val phase: ShutdownPhase,
    val totalPartitions: Int,
    val stoppedPartitions: Int,
    val failedPartitions: Int,
    val failures: List<PartitionFailure>,
    val durationMs: Long,
)

class LambdaResourcePartition(
    override val id: String,
    override val owns: List<String> = emptyList(),
    override val strictness: ResourceStrictness = ResourceStrictness.STRICT,
    override val shutdownPhase: ShutdownPhase = ShutdownPhase.DEPENDENCIES,
    private val stopAction: suspend () -> Unit,
    private val healthAction: () -> PartitionHealth = {
        PartitionHealth(id = id, healthy = true, detail = "ok")
    },
) : ResourcePartition {
    override suspend fun stop() = stopAction()
    override fun health(): PartitionHealth = healthAction()
}

class ResourceSupervisor {
    private val partitions = LinkedHashMap<String, ResourcePartition>()
    private val stopped = AtomicBoolean(false)

    @Synchronized
    fun register(partition: ResourcePartition) {
        partitions[partition.id] = partition
    }

    @Synchronized
    fun unregister(partitionId: String) {
        partitions.remove(partitionId)
    }

    @Synchronized
    fun reset() {
        partitions.clear()
        stopped.set(false)
    }

    @Synchronized
    fun snapshot(): List<ResourcePartition> = partitions.values.toList()

    suspend fun stopAll(): ResourceStopReport {
        if (stopped.getAndSet(true)) {
            return ResourceStopReport(
                success = true,
                totalPartitions = 0,
                stoppedPartitions = 0,
                failedPartitions = 0,
                failures = emptyList(),
            )
        }

        val toStop = snapshot().asReversed()
        val failures = mutableListOf<PartitionFailure>()
        var stoppedCount = 0
        val phaseReports = mutableListOf<PhaseStopReport>()

        for (phase in ShutdownPhase.entries) {
            val phasePartitions = toStop.filter { it.shutdownPhase == phase }
            if (phasePartitions.isEmpty()) {
                continue
            }

            val phaseFailures = mutableListOf<PartitionFailure>()
            var phaseStoppedCount = 0
            val phaseStartedAt = System.currentTimeMillis()

            for (partition in phasePartitions) {
                try {
                    withTimeout(partition.strictness.stopTimeoutMs) {
                        partition.stop()
                    }
                    stoppedCount++
                    phaseStoppedCount++
                } catch (_: TimeoutCancellationException) {
                    val failure = PartitionFailure(
                        partitionId = partition.id,
                        error = "停止超时(${partition.strictness.stopTimeoutMs}ms)",
                    )
                    failures += failure
                    phaseFailures += failure
                } catch (e: Exception) {
                    val failure = PartitionFailure(
                        partitionId = partition.id,
                        error = e.message ?: e::class.simpleName.orEmpty(),
                    )
                    failures += failure
                    phaseFailures += failure
                }
            }

            phaseReports += PhaseStopReport(
                phase = phase,
                totalPartitions = phasePartitions.size,
                stoppedPartitions = phaseStoppedCount,
                failedPartitions = phaseFailures.size,
                failures = phaseFailures,
                durationMs = System.currentTimeMillis() - phaseStartedAt,
            )
        }

        return ResourceStopReport(
            success = failures.isEmpty(),
            totalPartitions = toStop.size,
            stoppedPartitions = stoppedCount,
            failedPartitions = failures.size,
            failures = failures,
            phaseReports = phaseReports,
        )
    }
}
