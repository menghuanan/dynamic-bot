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
    @SerialName("llbot")
    LLBOT,
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
    val inboundPressureActive: Boolean = false,
    val inboundDroppedEvents: Int = 0,
    val outboundPressureActive: Boolean = false,
    val outboundDroppedEvents: Int = 0,
)

/**
 * 单个平台 transport 的 HttpClient / OkHttp 运行态快照。
 * 该模型独立于连接健康状态，专门承载连接池、调度器与 WebSocket 会话等资源观测信息。
 */
data class PlatformHttpClientSnapshot(
    val adapterName: String,
    val transportName: String,
    val connectionCount: Int? = null,
    val idleConnectionCount: Int? = null,
    val queuedCallsCount: Int? = null,
    val runningCallsCount: Int? = null,
    val webSocketSessionActive: Boolean = false,
    val note: String? = null,
)

/**
 * 平台层独立运行时观测快照。
 * manager 与 guardian 通过该模型聚合 transport 资源使用情况，而不是复用连接健康状态对象。
 */
data class PlatformObservabilitySnapshot(
    val clients: List<PlatformHttpClientSnapshot> = emptyList(),
    val note: String? = null,
) {
    companion object {
        /**
         * 为未初始化或暂未接入观测的场景提供统一空快照，避免外层守护逻辑继续判空。
         */
        fun empty(note: String? = null): PlatformObservabilitySnapshot {
            return PlatformObservabilitySnapshot(
                clients = emptyList(),
                note = note,
            )
        }
    }
}

sealed interface ImageSource {
    data class LocalFile(val path: String) : ImageSource

    data class RemoteUrl(val url: String) : ImageSource

    data class Binary(
        val bytes: ByteArray,
        val fileName: String = "image.png",
    ) : ImageSource

    companion object {
        /**
         * 按来源前缀推断图片输入类型，避免调用方在最常见的路径与 URL 场景手动分支。
         */
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
        /**
         * 构造文本消息片段，供业务层按统一发送模型拼装内容。
         */
        fun text(text: String): OutgoingPart = Text(text)

        /**
         * 直接使用已归一化的图片来源构造图片片段，避免发送层再关心来源细节。
         */
        fun image(source: ImageSource): OutgoingPart = Image(source)

        /**
         * 按常见路径或 URL 输入快速构造图片片段，减少调用方样板代码。
         */
        fun image(pathOrUrl: String): OutgoingPart = Image(ImageSource.from(pathOrUrl))

        /**
         * 构造 @全体 片段，供支持该能力的平台在发送阶段统一处理。
         */
        fun atAll(): OutgoingPart = MentionAll

        /**
         * 构造回复片段，保持各平台回复能力都走同一抽象入口。
         */
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
