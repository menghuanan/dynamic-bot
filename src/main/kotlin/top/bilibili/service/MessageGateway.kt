package top.bilibili.service

import top.bilibili.core.ContactId
import top.bilibili.napcat.MessageSegment

interface MessageGateway {
    suspend fun sendGroupMessage(groupId: Long, message: List<MessageSegment>): Boolean
    suspend fun sendPrivateMessage(userId: Long, message: List<MessageSegment>): Boolean
    suspend fun sendAdminMessage(message: String): Boolean
    suspend fun sendMessage(contact: ContactId, message: List<MessageSegment>): Boolean
}
