package top.bilibili.connector.onebot11.vendors.napcat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.onebot11.OneBot11Adapter
import top.bilibili.connector.onebot11.core.OneBot11MessageEvent
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment
import top.bilibili.connector.onebot11.core.OneBot11Transport
class NapCatAdapter(
    private val napCatClient: NapCatClient,
) : OneBot11Adapter(NapCatTransport(napCatClient)) {
    /**
     * NapCat 在通用 OneBot11 基础上额外声明 @全体能力，后续链路据此决定是否允许进入专用分支。
     */
    override fun declaredCapabilities(): Set<PlatformCapability> {
        return super.declaredCapabilities() + PlatformCapability.AT_ALL
    }

    /**
     * 由 NapCat vendor 适配层保留群可达性查询，避免该能力再次泄漏回通用协议核心。
     */
    override suspend fun isGroupReachable(groupId: Long): Boolean {
        return napCatClient.isBotInGroup(groupId)
    }

    /**
     * 由 NapCat vendor 适配层保留 @全体 能力查询，后续其他 vendor 可按自身能力覆写。
     */
    override suspend fun supportsAtAllInGroup(groupId: Long): Boolean {
        return napCatClient.canAtAllInGroup(groupId)
    }

    companion object {
        /**
         * 为显式 generic OneBot11 选择暴露 NapCat 传输桥接，避免启动层直接依赖 NapCatClient 细节。
         */
        fun transport(napCatClient: NapCatClient): OneBot11Transport {
            return NapCatTransport(napCatClient)
        }
    }
}

private class NapCatTransport(
    private val napCatClient: NapCatClient,
) : OneBot11Transport {
    override val eventFlow: Flow<OneBot11MessageEvent> =
        napCatClient.eventFlow.map(::toOneBot11MessageEvent)

    /**
     * 将通用传输启动请求桥接到 NapCat client，保持 OneBot11 适配层只依赖统一契约。
     */
    override fun start() {
        napCatClient.start()
    }

    /**
     * 将通用传输停机请求桥接到 NapCat client 的 suspend 生命周期。
     */
    override suspend fun stop() {
        napCatClient.stop()
    }

    /**
     * 将通用 OneBot11 消息段重新映射回 NapCat 段模型，保持现有发送行为不变。
     */
    override suspend fun sendMessage(
        chatType: PlatformChatType,
        targetId: Long,
        message: List<OneBot11MessageSegment>,
    ): Boolean {
        val napCatSegments = message.map(::toNapCatSegment)
        return when (chatType) {
            PlatformChatType.GROUP -> napCatClient.sendGroupMessage(targetId, napCatSegments)
            PlatformChatType.PRIVATE -> napCatClient.sendPrivateMessage(targetId, napCatSegments)
        }
    }

    /**
     * 汇总 NapCat 连接与发送背压状态，供平台层统一暴露运行时健康信息。
     */
    override fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = napCatClient.isConnected(),
            reconnectAttempts = napCatClient.getReconnectAttempts(),
            outboundPressureActive = napCatClient.isSendQueueFull(),
        )
    }

    /**
     * 将 NapCat 消息事件转换为通用 OneBot11 事件，供协议核心统一归一化。
     */
    private fun toOneBot11MessageEvent(event: MessageEvent): OneBot11MessageEvent {
        return OneBot11MessageEvent(
            messageType = event.messageType,
            messageId = event.messageId,
            userId = event.userId,
            message = event.message.map { segment -> OneBot11MessageSegment(segment.type, segment.data) },
            rawMessage = event.rawMessage,
            groupId = event.groupId,
            selfId = event.selfId,
        )
    }

    private fun toNapCatSegment(segment: OneBot11MessageSegment): MessageSegment {
        return MessageSegment(segment.type, segment.data)
    }
}
