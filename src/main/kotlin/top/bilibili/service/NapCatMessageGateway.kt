package top.bilibili.service

import org.slf4j.Logger
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.parsePlatformContact

class NapCatMessageGateway(
    private val platformAdapterProvider: () -> PlatformAdapter,
    private val adminContactProvider: () -> String?,
    private val logger: Logger,
) : MessageGateway {

    override suspend fun sendAdminMessage(message: String): Boolean {
        val adminContact = adminContactProvider()?.let(::parsePlatformContact)
        if (adminContact == null) {
            logger.warn("未配置管理员联系人，无法发送通知")
            return false
        }
        return sendMessage(adminContact, listOf(OutgoingPart.text(message)))
    }

    override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return platformAdapterProvider().sendMessage(contact, message)
    }
}
