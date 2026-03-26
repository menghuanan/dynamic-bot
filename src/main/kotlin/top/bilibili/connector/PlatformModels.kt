package top.bilibili.connector

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

@Serializable
enum class PlatformType {
    @SerialName("onebot11")
    ONEBOT11,
    @SerialName("qq_official")
    QQ_OFFICIAL,
}

@Serializable
enum class PlatformAdapterKind {
    @SerialName("napcat")
    NAPCAT,
    @SerialName("onebot11")
    ONEBOT11,
    @SerialName("qq_official")
    QQ_OFFICIAL,
}

@Serializable
enum class PlatformChatType {
    GROUP,
    PRIVATE,
}

data class PlatformContact(
    val platform: PlatformType,
    val type: PlatformChatType,
    val id: String,
)

data class PlatformRuntimeStatus(
    val connected: Boolean,
    val reconnectAttempts: Int,
    val sendQueueFull: Boolean,
)

sealed interface ImageSource {
    data class LocalFile(val path: String) : ImageSource

    data class RemoteUrl(val url: String) : ImageSource

    data class Binary(
        val bytes: ByteArray,
        val fileName: String = "image.png",
    ) : ImageSource

    companion object {
        fun from(pathOrUrl: String): ImageSource {
            return when {
                pathOrUrl.startsWith("http://") ||
                    pathOrUrl.startsWith("https://") ||
                    pathOrUrl.startsWith("file://") ||
                    pathOrUrl.startsWith("base64://") -> RemoteUrl(pathOrUrl)
                else -> LocalFile(pathOrUrl)
            }
        }
    }
}

sealed interface OutgoingPart {
    val type: String
    val data: Map<String, String>

    data class Text(val text: String) : OutgoingPart {
        override val type: String = "text"
        override val data: Map<String, String> = mapOf("text" to text)
    }

    data class Image(val source: ImageSource) : OutgoingPart {
        override val type: String = "image"
        override val data: Map<String, String> = emptyMap()
    }

    data object MentionAll : OutgoingPart {
        override val type: String = "at"
        override val data: Map<String, String> = mapOf("qq" to "all")
    }

    data class Reply(val messageId: Int) : OutgoingPart {
        override val type: String = "reply"
        override val data: Map<String, String> = mapOf("id" to messageId.toString())
    }

    companion object {
        fun text(text: String): OutgoingPart = Text(text)

        fun image(source: ImageSource): OutgoingPart = Image(source)

        fun image(pathOrUrl: String): OutgoingPart = Image(ImageSource.from(pathOrUrl))

        fun atAll(): OutgoingPart = MentionAll

        fun reply(messageId: Int): OutgoingPart = Reply(messageId)
    }
}

data class PlatformInboundMessage(
    val platform: PlatformType,
    val chatType: PlatformChatType,
    val chatContact: PlatformContact,
    val senderContact: PlatformContact,
    val selfContact: PlatformContact,
    val messageText: String,
    val searchTexts: List<String>,
    val hasMention: Boolean,
    val fromSelf: Boolean,
    val rawPayload: Any?,
) {
    // 为仍在迁移中的调用方保留字符串 ID 访问入口，避免再次回退到 Long 假设。
    val chatId: String get() = chatContact.id

    // 为仍在迁移中的调用方保留字符串发送者 ID。
    val senderId: String get() = senderContact.id

    // 为仍在迁移中的调用方保留字符串机器人自身 ID。
    val selfId: String get() = selfContact.id
}
