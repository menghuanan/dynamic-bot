package top.bilibili.napcat

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Contextual
import kotlinx.serialization.json.JsonObject

/**
 * 消息事件
 */
@Serializable
data class MessageEvent(
    val time: Long = 0,
    @SerialName("self_id")
    val selfId: Long = 0, // Bot 自己的 QQ 号
    @SerialName("post_type")
    val postType: String = "message",
    @SerialName("message_type")
    val messageType: String, // group, private
    @SerialName("sub_type")
    val subType: String = "",
    @SerialName("message_id")
    val messageId: Int,
    @SerialName("user_id")
    val userId: Long,
    val message: List<MessageSegment>,
    @SerialName("raw_message")
    val rawMessage: String,
    val font: Int = 0,
    val sender: Sender? = null,
    // 群消息特有字段
    @SerialName("group_id")
    val groupId: Long? = null,
    val anonymous: @Contextual Any? = null,
    // 私聊消息特有字段
    @SerialName("target_id")
    val targetId: Long? = null
)

/**
 * 消息段
 */
@Serializable
data class MessageSegment(
    val type: String,
    val data: Map<String, String> = emptyMap()
) {
    companion object {
        fun text(text: String) = MessageSegment("text", mapOf("text" to text))
        fun image(file: String) = MessageSegment("image", mapOf("file" to file))
        fun at(qq: Long) = MessageSegment("at", mapOf("qq" to qq.toString()))
        fun atAll() = MessageSegment("at", mapOf("qq" to "all"))
        fun reply(id: Int) = MessageSegment("reply", mapOf("id" to id.toString()))
    }
}

/**
 * 发送者信息
 */
@Serializable
data class Sender(
    @SerialName("user_id")
    val userId: Long,
    val nickname: String,
    val sex: String = "unknown",
    val age: Int = 0,
    // 群消息特有字段
    val card: String? = null,
    val area: String? = null,
    val level: String? = null,
    val role: String? = null,
    val title: String? = null
)

/**
 * 元事件（心跳等）
 */
@Serializable
data class MetaEvent(
    val time: Long = 0,
    @SerialName("post_type")
    val postType: String = "meta_event",
    @SerialName("meta_event_type")
    val metaEventType: String,
    @SerialName("sub_type")
    val subType: String? = null,
    val interval: Long? = null,
    val status: JsonObject? = null
)

/**
 * API 响应
 */
@Serializable
data class OneBotResponse(
    val status: String,
    val retcode: Int,
    val data: kotlinx.serialization.json.JsonElement? = null,
    val message: String = "",
    val wording: String = "",
    val echo: String? = null
)

/**
 * API 请求
 */
@Serializable
data class OneBotAction(
    val action: String,
    val params: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    val echo: String? = null
)
