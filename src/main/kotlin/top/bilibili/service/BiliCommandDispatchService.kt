package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.core.BiliCommandExecutor
import top.bilibili.core.BiliCommandProcessor
import top.bilibili.connector.PlatformContact

/**
 * 将 /bili 子命令分发收口到统一入口，隔离消息路由与具体命令实现。
 */
object BiliCommandDispatchService {
    /**
     * 复用命令处理器完成子命令分发，并在这里统一兜底异常返回。
     */
    suspend fun dispatch(chatContact: PlatformContact, senderContact: PlatformContact, message: String) {
        try {
            val args = message.substringAfter("/bili ").trim().split(Regex("\\s+"))
            BiliCommandProcessor.process(message, object : BiliCommandExecutor {
                override suspend fun add() = SubscriptionCommandService.handleAdd(chatContact, senderContact, args)
                override suspend fun remove() = SubscriptionCommandService.handleRemove(chatContact, senderContact, args)
                override suspend fun list() = SubscriptionCommandService.handleList(chatContact, senderContact, args)
                override suspend fun color() = SettingsCommandService.handleColor(chatContact, senderContact, args)
                override suspend fun groups() = GroupCommandService.listGroups(chatContact, senderContact)
                override suspend fun group() = GroupCommandService.handle(chatContact, senderContact, args)
                override suspend fun filter() = FilterCommandService.handle(chatContact, senderContact, args)
                override suspend fun template() = PresentationCommandService.handleTemplate(chatContact, senderContact, args)
                override suspend fun atall() = SettingsCommandService.handleAtAll(chatContact, senderContact, args)
                override suspend fun config() = SettingsCommandService.handleConfig(chatContact, senderContact, args)
                override suspend fun admin() = AdminCommandService.handle(chatContact, senderContact, args)
                override suspend fun blacklist() = BlacklistCommandService.handle(chatContact, senderContact, args)
                override suspend fun help() = PresentationCommandService.sendHelp(chatContact, senderContact)
                override suspend fun unknown(command: String) {
                    val msg = "未知命令: $command\n使用 /bili help 查看帮助"
                    sendText(chatContact, msg)
                }
            })
        } catch (e: Exception) {
            BiliBiliBot.logger.error("处理 /bili 命令失败: ${e.message}", e)
            val msg = "命令执行失败: ${e.message}"
            sendText(chatContact, msg)
        }
    }
}

