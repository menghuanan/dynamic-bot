package top.bilibili.connector.onebot11.vendors.llbot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * llbot 入站消息事件沿用 OneBot11 标准字段，供 vendor client 解析后再归一到平台事件。
 */
@Serializable
internal data class LlBotMessageEvent(
    @SerialName("post_type")
    val postType: String = "message",
    @SerialName("message_type")
    val messageType: String,
    @SerialName("message_id")
    val messageId: Int,
    @SerialName("user_id")
    val userId: Long,
    val message: List<LlBotMessageSegment>,
    @SerialName("raw_message")
    val rawMessage: String,
    @SerialName("group_id")
    val groupId: Long? = null,
    @SerialName("self_id")
    val selfId: Long = 0L,
)

/**
 * llbot 消息段仅保留通用 OneBot11 `type + data` 结构，避免把 vendor 细节带进 adapter 核心。
 */
@Serializable
internal data class LlBotMessageSegment(
    val type: String,
    val data: Map<String, String> = emptyMap(),
)

/**
 * llbot API 响应遵循 OneBot11 标准返回体，用于发送与能力探测请求的统一判定。
 */
@Serializable
internal data class LlBotResponse(
    val status: String,
    val retcode: Int,
    val data: JsonElement? = null,
    val message: String = "",
    val wording: String = "",
    val echo: String? = null,
)

/**
 * llbot API 请求统一走 OneBot11 action 结构，便于 vendor client 发送标准查询与消息动作。
 */
@Serializable
internal data class LlBotAction(
    val action: String,
    val params: Map<String, JsonElement> = emptyMap(),
    val echo: String? = null,
)
