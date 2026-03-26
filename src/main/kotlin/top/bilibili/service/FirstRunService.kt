package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.config.ConfigManager
import top.bilibili.config.BotConfig
import top.bilibili.core.BiliBiliBot
import top.bilibili.connector.OutgoingPart
import top.bilibili.utils.parsePlatformContact

object FirstRunService {
    suspend fun checkFirstRun(config: BotConfig) {
        if (config.firstRunFlag != 0) return

        BiliBiliBot.logger.info("首次运行检查: 检测到 first_run_flag=0，准备发送欢迎消息...")

        val adminContact = BiliConfigManager.config.normalizedAdminSubject()?.let(::parsePlatformContact)
        if (adminContact == null) {
            BiliBiliBot.logger.warn("首次运行检查：未配置管理员联系人，无法发送欢迎消息")
            return
        }

        val welcomeMsg = """
            欢迎使用 BiliBili 动态推送 Bot
            /bili help - 显示命令帮助
            /login 或 登录 - 扫码登录
            /check - 手动触发检查
            /add <UID> - 快速订阅
            /del <UID> - 快速取消订阅
            /list - 查看订阅列表
            /black [QQ号] - 添加黑名单
            /unblock [QQ号] - 取消黑名单
            /black list - 查看黑名单
        """.trimIndent()

        try {
            val success = MessageGatewayProvider.require().sendMessage(adminContact, listOf(OutgoingPart.text(welcomeMsg)))
            if (success) {
                BiliBiliBot.logger.info("欢迎消息发送成功")
                config.firstRunFlag = 1
                ConfigManager.saveConfig()
                BiliBiliBot.logger.info("已更新 first_run_flag 为 1 并保存配置")
            } else {
                BiliBiliBot.logger.warn("欢迎消息发送失败（可能管理员未开私聊）")
            }
        } catch (e: Exception) {
            BiliBiliBot.logger.error("发送欢迎消息时发生异常: ${e.message}", e)
        }
    }
}

