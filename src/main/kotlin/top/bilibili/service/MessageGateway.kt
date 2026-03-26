package top.bilibili.service

import top.bilibili.connector.OutgoingPart
import top.bilibili.core.ContactId

interface MessageGateway {
    suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean
    suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean
    suspend fun sendAdminMessage(message: String): Boolean
    suspend fun sendMessage(contact: ContactId, message: List<OutgoingPart>): Boolean
}
