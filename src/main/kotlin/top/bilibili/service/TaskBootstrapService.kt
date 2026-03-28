package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.TaskResourcePolicyRegistry
import top.bilibili.tasker.CacheClearTasker
import top.bilibili.tasker.DynamicCheckTasker
import top.bilibili.tasker.DynamicMessageTasker
import top.bilibili.tasker.ListenerTasker
import top.bilibili.tasker.LiveCheckTasker
import top.bilibili.tasker.LiveCloseCheckTasker
import top.bilibili.tasker.LiveMessageTasker
import top.bilibili.tasker.LogClearTasker
import top.bilibili.tasker.ProcessGuardian
import top.bilibili.tasker.SendTasker
import top.bilibili.tasker.SkiaCleanupTasker

/**
 * 统一启动后台任务集合，避免主启动流程手工维护任务顺序和覆盖校验。
 */
object TaskBootstrapService {
    private val startupTaskNames = listOf(
        "ListenerTasker",
        "DynamicCheckTasker",
        "LiveCheckTasker",
        "LiveCloseCheckTasker",
        "DynamicMessageTasker",
        "LiveMessageTasker",
        "SendTasker",
        "CacheClearTasker",
        "LogClearTasker",
        "SkiaCleanupTasker",
        "ProcessGuardian",
    )

    /**
     * 校验任务资源策略后按既定顺序启动所有后台任务。
     */
    fun startTasks() {
        try {
            BiliBiliBot.logger.info("正在启动任务...")
            TaskResourcePolicyRegistry.validateCoverage(startupTaskNames)

            BiliBiliBot.logger.info("启动链接解析任务...")
            ListenerTasker.start()

            BiliBiliBot.logger.info("启动动态检查任务...")
            DynamicCheckTasker.start()

            BiliBiliBot.logger.info("启动直播检查任务...")
            LiveCheckTasker.start()

            BiliBiliBot.logger.info("启动直播结束检查任务...")
            LiveCloseCheckTasker.start()

            BiliBiliBot.logger.info("启动动态消息任务...")
            DynamicMessageTasker.start()

            BiliBiliBot.logger.info("启动直播消息任务...")
            LiveMessageTasker.start()

            BiliBiliBot.logger.info("启动发送任务...")
            SendTasker.start()

            BiliBiliBot.logger.info("启动缓存清理任务...")
            CacheClearTasker.start()

            BiliBiliBot.logger.info("启动日志清理任务...")
            LogClearTasker.start()

            BiliBiliBot.logger.info("启动 Skia 清理任务...")
            SkiaCleanupTasker.start()

            BiliBiliBot.logger.info("启动进程守护任务...")
            ProcessGuardian.start()

            BiliBiliBot.logger.info("所有任务已启动")
        } catch (e: Exception) {
            BiliBiliBot.logger.error("启动任务失败: ${e.message}", e)
        }
    }
}
