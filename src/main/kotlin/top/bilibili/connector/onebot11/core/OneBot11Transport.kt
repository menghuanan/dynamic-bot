package top.bilibili.connector.onebot11.core

import kotlinx.coroutines.flow.Flow
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformRuntimeStatus

interface OneBot11Transport {
    val eventFlow: Flow<OneBot11MessageEvent>

    /**
     * 启动底层 OneBot11 连接与事件循环，供适配器统一接通协议传输。
     */
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

    /**
     * 返回 OneBot11 传输当前运行状态，供平台层统一读取连接与重连信息。
     */
    fun runtimeStatus(): PlatformRuntimeStatus

    /**
     * 返回 OneBot11 传输层的运行时资源观测快照，供 guardian 后续统一汇总 HttpClient / OkHttp 资源状态。
     */
    fun runtimeObservability(): PlatformObservabilitySnapshot
}
