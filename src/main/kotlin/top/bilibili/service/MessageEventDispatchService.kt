package top.bilibili.service

import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.napcat.MessageEvent

object MessageEventDispatchService {
    suspend fun handleMessageEvent(event: MessageEvent) {
        BusinessLifecycleManager.run(
            owner = "MessageEventDispatchService",
            operation = "event:${event.messageType}",
        ) {
            try {
                when (event.messageType) {
                    "group" -> handleGroupMessage(event)
                    "private" -> handlePrivateMessage(event)
                    else -> BiliBiliBot.logger.debug("收到未知类型的消息: ${event.messageType}")
                }
            } catch (e: Exception) {
                BiliBiliBot.logger.error("处理消息事件失败: ${e.message}", e)
            }
        }
    }

    private suspend fun handleGroupMessage(event: MessageEvent) {
        val groupId = event.groupId ?: return
        val userId = event.userId
        val simplified = MessageLogFormatter.simplify(event.rawMessage) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("群消息 [$groupId] 来自 $userId: $simplified")
        MessageCommandRouterService.handleGroupMessage(event)
    }

    private suspend fun handlePrivateMessage(event: MessageEvent) {
        val userId = event.userId
        val simplified = MessageLogFormatter.simplify(event.rawMessage) { length ->
            BiliBiliBot.logger.warn("消息过长 ($length)，已截断")
        }
        BiliBiliBot.logger.info("私聊消息 来自 $userId: $simplified")
        MessageCommandRouterService.handlePrivateMessage(event)
    }
}
