package top.bilibili.service

import top.bilibili.connector.CapabilityGuard
import top.bilibili.connector.PlatformCapabilityService
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
        if (!canReplyToCurrentEvent(event)) {
            return
        }
        val simplified = MessageLogFormatter.simplify(event.messageText) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("群消息[{}] 来自 {}: {}", event.chatContact.id, event.senderContact.id, simplified)
        MessageCommandRouterService.handleGroupMessage(event)
    }

    private suspend fun handlePrivateMessage(event: PlatformInboundMessage) {
        if (!canReplyToCurrentEvent(event)) {
            return
        }
        val simplified = MessageLogFormatter.simplify(event.messageText) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("私聊消息 来自 {}: {}", event.senderContact.id, simplified)
        MessageCommandRouterService.handlePrivateMessage(event)
    }

    /**
     * 收到事件但当前联系人不可回复时，仅停止当前事件处理，避免把不可响应上下文继续送入命令链。
     */
    private suspend fun canReplyToCurrentEvent(event: PlatformInboundMessage): Boolean {
        val sendGuard = PlatformCapabilityService.guardMessageSend(event.chatContact)
        if (sendGuard.stopCurrentOperation) {
            BiliBiliBot.logger.warn(
                "{}: 停止当前会话 {} 的事件处理",
                sendGuard.marker ?: CapabilityGuard.UNSUPPORTED_MESSAGE,
                event.chatContact.id,
            )
            return false
        }
        return true
    }
}
