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

    override suspend fun main() {
        // 1. 检查是否空闲超时
        if (DrawingQueueManager.isIdleTimeout()) {
            logger.info("Skia 空闲超时，执行清理")
            SkiaManager.performCleanup()
        }

        // 2. 获取状态
        val status = SkiaManager.getStatus()

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
