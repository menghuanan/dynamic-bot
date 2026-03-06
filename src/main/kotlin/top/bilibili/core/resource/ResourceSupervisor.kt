package top.bilibili.core.resource

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

interface ResourcePartition {
    val id: String
    val owns: List<String>
    val strictness: ResourceStrictness

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
)

class LambdaResourcePartition(
    override val id: String,
    override val owns: List<String> = emptyList(),
    override val strictness: ResourceStrictness = ResourceStrictness.STRICT,
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

        for (partition in toStop) {
            try {
                withTimeout(partition.strictness.stopTimeoutMs) {
                    partition.stop()
                }
                stoppedCount++
            } catch (_: TimeoutCancellationException) {
                failures += PartitionFailure(
                    partitionId = partition.id,
                    error = "stop timeout(${partition.strictness.stopTimeoutMs}ms)",
                )
            } catch (e: Exception) {
                failures += PartitionFailure(
                    partitionId = partition.id,
                    error = e.message ?: e::class.simpleName.orEmpty(),
                )
            }
        }

        return ResourceStopReport(
            success = failures.isEmpty(),
            totalPartitions = toStop.size,
            stoppedPartitions = stoppedCount,
            failedPartitions = failures.size,
            failures = failures,
        )
    }
}
