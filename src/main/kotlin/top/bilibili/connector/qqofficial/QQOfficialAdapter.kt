package top.bilibili.connector.qqofficial

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.core.ContactId

class QQOfficialAdapter : PlatformAdapter {
    override val eventFlow: Flow<PlatformInboundMessage> = emptyFlow()

    override fun start() = Unit

    override fun stop() = Unit

    override suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean = false

    override suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean = false

    override suspend fun sendMessage(contact: ContactId, message: List<OutgoingPart>): Boolean = false

    override fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = false,
            reconnectAttempts = 0,
            sendQueueFull = false,
        )
    }

    override suspend fun isGroupReachable(groupId: Long): Boolean = false

    override suspend fun canAtAll(groupId: Long): Boolean = false
}
