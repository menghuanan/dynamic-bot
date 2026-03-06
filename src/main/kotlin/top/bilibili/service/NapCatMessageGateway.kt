package top.bilibili.service

import org.slf4j.Logger
import top.bilibili.core.ContactId
import top.bilibili.napcat.MessageSegment
import top.bilibili.napcat.NapCatClient

class NapCatMessageGateway(
    private val napCatProvider: () -> NapCatClient,
    private val adminIdProvider: () -> Long,
    private val logger: Logger
) : MessageGateway {

    override suspend fun sendGroupMessage(groupId: Long, message: List<MessageSegment>): Boolean {
        return napCatProvider().sendGroupMessage(groupId, message)
    }

    override suspend fun sendPrivateMessage(userId: Long, message: List<MessageSegment>): Boolean {
        return napCatProvider().sendPrivateMessage(userId, message)
    }

    override suspend fun sendAdminMessage(message: String): Boolean {
        val adminId = adminIdProvider()
        if (adminId <= 0L) {
            logger.warn("未配置管理员 ID，无法发送通知")
            return false
        }
        return sendPrivateMessage(adminId, listOf(MessageSegment.text(message)))
    }

    override suspend fun sendMessage(contact: ContactId, message: List<MessageSegment>): Boolean {
        return when (contact.type) {
            "group" -> sendGroupMessage(contact.id, message)
            "private" -> sendPrivateMessage(contact.id, message)
            else -> {
                logger.warn("未知的联系人类型: ${contact.type}")
                false
            }
        }
    }
}
