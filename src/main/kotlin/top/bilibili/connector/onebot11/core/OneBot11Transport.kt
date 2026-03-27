package top.bilibili.connector.onebot11.core

import kotlinx.coroutines.flow.Flow
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformRuntimeStatus

interface OneBot11Transport {
    val eventFlow: Flow<OneBot11MessageEvent>

    fun start()

    /**
     * 统一提供可挂起的停机入口，避免 generic transport 关闭时再使用 runBlocking 桥接。
     */
    suspend fun stop()

    /**
     * 统一发送 OneBot11 消息段，避免上层继续感知具体 vendor 客户端。
     */
    suspend fun sendMessage(
        chatType: PlatformChatType,
        targetId: Long,
        message: List<OneBot11MessageSegment>,
    ): Boolean

    fun runtimeStatus(): PlatformRuntimeStatus
}
