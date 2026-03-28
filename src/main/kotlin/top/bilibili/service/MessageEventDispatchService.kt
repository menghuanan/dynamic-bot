package top.bilibili.service

import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager

/**
 * 统一接收平台消息事件并分发到对应命令路由，避免入口层直接处理会话差异。
 */
object MessageEventDispatchService {
    /**
     * 在事件入口统一过滤自回显消息并按会话类型分发到下游处理器。
     */
    suspend fun handleMessageEvent(event: PlatformInboundMessage) {
        // Bot 自己发出的回显消息不应再次进入命令链，否则会制造误报和超时。
        if (event.fromSelf) {
            BiliBiliBot.logger.debug("忽略 Bot 自己发送的消息事件: chat={}, sender={}", event.chatContact.id, event.senderContact.id)
            return
        }
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
        val simplified = MessageLogFormatter.simplify(event.messageText) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("群消息[{}] 来自 {}: {}", event.chatContact.id, event.senderContact.id, simplified)
        MessageCommandRouterService.handleGroupMessage(event)
    }

    private suspend fun handlePrivateMessage(event: PlatformInboundMessage) {
        val simplified = MessageLogFormatter.simplify(event.messageText) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("私聊消息 来自 {}: {}", event.senderContact.id, simplified)
        MessageCommandRouterService.handlePrivateMessage(event)
    }
}
