package top.bilibili.service

import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager

object MessageEventDispatchService {
    suspend fun handleMessageEvent(event: PlatformInboundMessage) {
        BusinessLifecycleManager.run(
            owner = "MessageEventDispatchService",
            operation = "event:${event.chatType.name.lowercase()}",
        ) {
            try {
                when (event.chatType) {
                    PlatformChatType.GROUP -> handleGroupMessage(event)
                    PlatformChatType.PRIVATE -> handlePrivateMessage(event)
                }
            } catch (e: Exception) {
                BiliBiliBot.logger.error("处理消息事件失败: ${e.message}", e)
            }
        }
    }

    private suspend fun handleGroupMessage(event: PlatformInboundMessage) {
        val groupId = event.chatId.toLongOrNull() ?: return
        val userId = event.senderId.toLongOrNull() ?: return
        val simplified = MessageLogFormatter.simplify(event.messageText) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("群消息[$groupId] 来自 $userId: $simplified")
        MessageCommandRouterService.handleGroupMessage(event)
    }

    private suspend fun handlePrivateMessage(event: PlatformInboundMessage) {
        val userId = event.senderId.toLongOrNull() ?: return
        val simplified = MessageLogFormatter.simplify(event.messageText) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("私聊消息 来自 $userId: $simplified")
        MessageCommandRouterService.handlePrivateMessage(event)
    }
}
