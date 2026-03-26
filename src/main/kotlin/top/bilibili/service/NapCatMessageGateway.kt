package top.bilibili.service

import org.slf4j.Logger
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.core.ContactId

class NapCatMessageGateway(
    private val platformAdapterProvider: () -> PlatformAdapter,
    private val adminIdProvider: () -> Long,
    private val logger: Logger,
) : MessageGateway {

    override suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean {
        return platformAdapterProvider().sendGroupMessage(groupId, message)
    }

    override suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean {
        return platformAdapterProvider().sendPrivateMessage(userId, message)
    }

    override suspend fun sendAdminMessage(message: String): Boolean {
        val adminId = adminIdProvider()
        if (adminId <= 0L) {
            logger.warn("未配置管理员 ID，无法发送通知")
            return false
        }
        return sendPrivateMessage(adminId, listOf(OutgoingPart.text(message)))
    }

    override suspend fun sendMessage(contact: ContactId, message: List<OutgoingPart>): Boolean {
        return platformAdapterProvider().sendMessage(contact, message)
    }
}
