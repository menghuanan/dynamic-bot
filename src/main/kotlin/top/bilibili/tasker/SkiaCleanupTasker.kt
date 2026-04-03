package top.bilibili.tasker

import top.bilibili.skia.DrawingQueueManager
import top.bilibili.skia.SkiaConfig
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.logger

/**
 * Skia 资源清理任务
 * 定期检查 Skia 资源状态，在空闲时执行清理
 */
object SkiaCleanupTasker : BiliTasker() {
    override var interval: Int = 60  // 每 60 秒检查一次

    // 记录最近一次周期清理时间，避免高频任务在短时间内反复重置全局缓存。
    private var lastPeriodicCleanupAt: Long = System.currentTimeMillis()

    override suspend fun main() {
        // 同时支持 idle timeout 和固定清理周期，避免高频但非持续占用场景长期跳过缓存清理。
        var status = try {
            SkiaManager.getStatus()
        } catch (e: Exception) {
            logger.warn("获取 Skia 状态失败: ${e.message}")
            return
        }

        // 队列空闲时按 cleanupIntervalMs 周期补做清理，防止仅靠 idle timeout 导致 ParagraphCache 持续积累。
        val queueStatusBeforeCleanup = status.queueStatus
        val now = System.currentTimeMillis()
        val periodicCleanupDue =
            queueStatusBeforeCleanup.activeCount == 0 &&
                queueStatusBeforeCleanup.pendingCount == 0 &&
                now - lastPeriodicCleanupAt >= SkiaConfig.cleanupIntervalMs

        try {
            if (status.memoryUsage >= SkiaConfig.memoryCriticalThreshold) {
                // 达到临界阈值时优先触发紧急清理，尽量尽快压低 native 缓存峰值。
                logger.warn(
                    "Skia 内存达到临界阈值: ${(status.memoryUsage * 100).toInt()}% " +
                        "(阈值: ${(SkiaConfig.memoryCriticalThreshold * 100).toInt()}%)，执行紧急清理",
                )
                SkiaManager.performEmergencyCleanup()
                status = SkiaManager.getStatus()
            } else if (DrawingQueueManager.isIdleTimeout()) {
                logger.debug("Skia 空闲超时，执行清理")
                SkiaManager.performCleanup()
                lastPeriodicCleanupAt = now
                status = SkiaManager.getStatus()
            } else if (periodicCleanupDue) {
                logger.debug("Skia 达到周期清理间隔，执行清理")
                SkiaManager.performCleanup()
                lastPeriodicCleanupAt = now
                status = SkiaManager.getStatus()
            }
        } catch (e: Exception) {
            logger.warn("Skia 清理检查失败: ${e.message}")
        }

        // 3. 检查内存警告
        if (status.memoryUsage >= SkiaConfig.memoryWarningThreshold) {
            logger.warn(
                "Skia 内存使用率过高: ${(status.memoryUsage * 100).toInt()}% " +
                "(阈值: ${(SkiaConfig.memoryWarningThreshold * 100).toInt()}%)"
            )
        }

        // 4. 记录队列状态（当队列非空时）
        val queueStatus = status.queueStatus
        if (queueStatus.pendingCount > 0 || queueStatus.activeCount > 0) {
            logger.debug(
                "Skia 队列状态: 待处理=${queueStatus.pendingCount}, " +
                "活动中=${queueStatus.activeCount}, " +
                "已满=${queueStatus.isFull}"
            )
        }

        // 5. 记录整体状态
        logger.debug(
            "Skia 状态: 模式=${status.mode}, " +
            "内存=${(status.memoryUsage * 100).toInt()}%, " +
            "绘图=${status.totalDrawingCount}, " +
            "清理=${status.totalCleanupCount}"
        )
    }
}
