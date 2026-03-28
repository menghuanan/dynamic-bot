package top.bilibili.core.resource

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 资源停止时的阶段划分。
 */
enum class ShutdownPhase {
    INGRESS,
    WORKERS,
    CHANNELS,
    DEPENDENCIES,
    ROOT_SCOPE,
}

/**
 * 可被资源总管托管的资源分区约定。
 */
interface ResourcePartition {
    val id: String
    val owns: List<String>
    val strictness: ResourceStrictness
    val shutdownPhase: ShutdownPhase

    /**
     * 执行当前资源分区的停止动作。
     */
    suspend fun stop()

    /**
     * 返回当前资源分区的健康状态。
     */
    fun health(): PartitionHealth = PartitionHealth(id = id, healthy = true, detail = "ok")
}

/**
 * 单个资源分区的健康状态快照。
 */
data class PartitionHealth(
    val id: String,
    val healthy: Boolean,
    val detail: String,
)

/**
 * 单个资源分区停止失败的摘要信息。
 */
data class PartitionFailure(
    val partitionId: String,
    val error: String,
)

/**
 * 一次整体停机过程的汇总结果。
 */
data class ResourceStopReport(
    val success: Boolean,
    val totalPartitions: Int,
    val stoppedPartitions: Int,
    val failedPartitions: Int,
    val failures: List<PartitionFailure>,
    val phaseReports: List<PhaseStopReport> = emptyList(),
)

/**
 * 单个停机阶段的执行结果。
 */
data class PhaseStopReport(
    val phase: ShutdownPhase,
    val totalPartitions: Int,
    val stoppedPartitions: Int,
    val failedPartitions: Int,
    val failures: List<PartitionFailure>,
    val durationMs: Long,
)

/**
 * 使用 lambda 快速声明资源分区的便捷实现。
 */
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

/**
 * 按阶段统一注册、观测并停止运行期资源。
 */
class ResourceSupervisor {
    private val partitions = LinkedHashMap<String, ResourcePartition>()
    private val stopped = AtomicBoolean(false)

    /**
     * 注册一个可由资源总管统一回收的资源分区。
     */
    @Synchronized
    fun register(partition: ResourcePartition) {
        partitions[partition.id] = partition
    }

    /**
     * 按 ID 取消注册一个资源分区。
     */
    @Synchronized
    fun unregister(partitionId: String) {
        partitions.remove(partitionId)
    }

    /**
     * 清空当前注册表，并允许后续重新执行停机流程。
     */
    @Synchronized
    fun reset() {
        partitions.clear()
        stopped.set(false)
    }

    /**
     * 返回当前已注册资源分区的快照。
     */
    @Synchronized
    fun snapshot(): List<ResourcePartition> = partitions.values.toList()

    /**
     * 按预定义阶段依次停止全部资源分区。
     */
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
            // 显式分阶段停止是为了保证入口、工作协程、通道和底层依赖按依赖方向逆序回收。
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
