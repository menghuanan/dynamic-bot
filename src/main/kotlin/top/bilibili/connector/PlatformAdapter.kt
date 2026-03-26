package top.bilibili.connector

import kotlinx.coroutines.flow.Flow
import top.bilibili.core.ContactId

interface PlatformAdapter {
    val eventFlow: Flow<PlatformInboundMessage>

    fun start()

    fun stop()

    suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean

    suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean

    suspend fun sendMessage(contact: ContactId, message: List<OutgoingPart>): Boolean

    fun runtimeStatus(): PlatformRuntimeStatus

    suspend fun isGroupReachable(groupId: Long): Boolean

    suspend fun canAtAll(groupId: Long): Boolean
}
