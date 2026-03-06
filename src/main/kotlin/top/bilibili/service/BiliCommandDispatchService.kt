package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.core.BiliCommandExecutor
import top.bilibili.core.BiliCommandProcessor
import top.bilibili.napcat.MessageSegment

object BiliCommandDispatchService {
    suspend fun dispatch(contactId: Long, userId: Long, message: String, isGroup: Boolean) {
        try {
            val args = message.substringAfter("/bili ").trim().split(Regex("\\s+"))
            BiliCommandProcessor.process(message, object : BiliCommandExecutor {
                override suspend fun add() = SubscriptionCommandService.handleAdd(contactId, userId, args, isGroup)
                override suspend fun remove() = SubscriptionCommandService.handleRemove(contactId, userId, args, isGroup)
                override suspend fun list() = SubscriptionCommandService.handleList(contactId, userId, args, isGroup)
                override suspend fun color() = SettingsCommandService.handleColor(contactId, userId, args, isGroup)
                override suspend fun groups() = GroupCommandService.listGroups(contactId, userId, isGroup)
                override suspend fun group() = GroupCommandService.handle(contactId, userId, args, isGroup)
                override suspend fun filter() = FilterCommandService.handle(contactId, userId, args, isGroup)
                override suspend fun template() = PresentationCommandService.handleTemplate(contactId, userId, args, isGroup)
                override suspend fun atall() = SettingsCommandService.handleAtAll(contactId, userId, args, isGroup)
                override suspend fun config() = SettingsCommandService.handleConfig(contactId, userId, args, isGroup)
                override suspend fun admin() = AdminCommandService.handle(contactId, userId, args, isGroup)
                override suspend fun blacklist() = BlacklistCommandService.handle(contactId, userId, args, isGroup)
                override suspend fun help() = PresentationCommandService.sendHelp(contactId, userId, isGroup)
                override suspend fun unknown(command: String) {
                    val msg = "未知命令: $command\n使用 /bili help 查看帮助"
                    if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
                    else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
                }
            })
        } catch (e: Exception) {
            BiliBiliBot.logger.error("处理 /bili 命令失败: ${e.message}", e)
            val msg = "命令执行失败: ${e.message}"
            if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
            else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
        }
    }
}

