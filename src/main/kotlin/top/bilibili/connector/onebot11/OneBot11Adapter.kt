package top.bilibili.connector.onebot11

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import top.bilibili.connector.onebot11.core.OneBot11MessageEvent
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment
import top.bilibili.connector.onebot11.core.OneBot11Transport
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.Base64

open class OneBot11Adapter(
    private val transport: OneBot11Transport,
) : PlatformAdapter {
    override val eventFlow: Flow<PlatformInboundMessage> =
        transport.eventFlow.map(::normalize)

    /**
     * 通用 OneBot11 核心先声明基础发送能力；vendor 扩展能力在各自适配层覆写追加。
     */
    override fun declaredCapabilities(): Set<PlatformCapability> {
        return setOf(
            PlatformCapability.SEND_MESSAGE,
            PlatformCapability.SEND_IMAGES,
            PlatformCapability.REPLY,
            PlatformCapability.LINK_RESOLVE,
        )
    }

    override fun start() {
        transport.start()
    }

    override suspend fun stop() {
        transport.stop()
    }

    override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        if (contact.platform != PlatformType.ONEBOT11) return false
        val numericId = contact.id.toLongOrNull() ?: return false
        return transport.sendMessage(contact.type, numericId, toMessageSegments(message))
    }

    override fun runtimeStatus(): PlatformRuntimeStatus {
        return transport.runtimeStatus()
    }

    override suspend fun isContactReachable(contact: PlatformContact): Boolean {
        if (contact.platform != PlatformType.ONEBOT11) return false
        if (contact.type != PlatformChatType.GROUP) return true
        val groupId = contact.id.toLongOrNull() ?: return false
        return isGroupReachable(groupId)
    }

    override suspend fun canAtAll(contact: PlatformContact): Boolean {
        if (contact.platform != PlatformType.ONEBOT11 || contact.type != PlatformChatType.GROUP) {
            return false
        }
        val groupId = contact.id.toLongOrNull() ?: return false
        return supportsAtAllInGroup(groupId)
    }

    /**
     * 通用 OneBot11 默认只假设群上下文存在；vendor 如能精确查询可覆写该入口。
     */
    override suspend fun isGroupReachable(groupId: Long): Boolean {
        return true
    }

    /**
     * 通用 OneBot11 默认不声明 @全体 能力；NapCat 等 vendor 在适配层显式覆写。
     */
    protected open suspend fun supportsAtAllInGroup(groupId: Long): Boolean {
        return false
    }

    private fun toMessageSegments(parts: List<OutgoingPart>): List<OneBot11MessageSegment> {
        return parts.map { part ->
            when (part) {
                is OutgoingPart.Text -> OneBot11MessageSegment("text", mapOf("text" to part.text))
                is OutgoingPart.Image -> OneBot11MessageSegment("image", mapOf("file" to resolveImageFile(part.source)))
                is OutgoingPart.MentionAll -> OneBot11MessageSegment("at", mapOf("qq" to "all"))
                is OutgoingPart.Reply -> OneBot11MessageSegment("reply", mapOf("id" to part.messageId.toString()))
            }
        }
    }

    private fun resolveImageFile(source: ImageSource): String {
        return when (source) {
            is ImageSource.LocalFile -> {
                if (source.path.startsWith("base64://")) {
                    source.path
                } else {
                    encodeLocalImageFile(resolveLocalImageFile(source.path))
                }
            }
            is ImageSource.RemoteUrl -> source.url
            is ImageSource.Binary -> encodeBinaryImage(source.bytes)
        }
    }

    /**
     * 将本地图片文件读取为 base64 transport payload，确保 OneBot11 发送端不再透传 file:// URL。
     */
    private fun encodeLocalImageFile(file: File): String {
        require(file.exists() && file.isFile) {
            "OneBot11 local image file does not exist: ${file.absolutePath}"
        }
        return encodeBinaryImage(file.readBytes())
    }

    /**
     * 统一生成 base64:// 图片载荷，避免 LocalFile 与 Binary 走出两套编码格式。
     */
    private fun encodeBinaryImage(bytes: ByteArray): String {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "base64://$encoded"
    }

    /**
     * 兼容普通路径和 file:// 路径输入，统一收口为适配器可读取的本地文件对象。
     */
    private fun resolveLocalImageFile(path: String): File {
        if (!path.startsWith("file://")) {
            return File(path)
        }
        return runCatching {
            Paths.get(URI(path)).toFile()
        }.getOrElse {
            val normalized = path.removePrefix("file:///").removePrefix("file://")
            File(normalized)
        }
    }

    companion object {
        fun normalize(event: OneBot11MessageEvent): PlatformInboundMessage {
            val chatType = if (event.messageType == "group") {
                PlatformChatType.GROUP
            } else {
                PlatformChatType.PRIVATE
            }

            val chatId = when (chatType) {
                PlatformChatType.GROUP -> event.groupId?.toString().orEmpty()
                PlatformChatType.PRIVATE -> event.userId.toString()
            }

            val textContent = event.message
                .filter { it.type == "text" }
                .joinToString(separator = "") { it.data["text"].orEmpty() }
                .trim()

            val searchTexts = buildList {
                if (textContent.isNotEmpty()) {
                    add(textContent)
                } else if (shouldUseRawMessageForSearch(event.rawMessage)) {
                    add(event.rawMessage.trim())
                }
                extractMiniAppUrl(event.message)?.let(::add)
            }.distinct()

            return PlatformInboundMessage(
                platform = PlatformType.ONEBOT11,
                chatType = chatType,
                chatContact = PlatformContact(PlatformType.ONEBOT11, chatType, chatId),
                senderContact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, event.userId.toString()),
                selfContact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, event.selfId.toString()),
                messageText = event.rawMessage,
                searchTexts = searchTexts,
                hasMention = event.message.any { it.type == "at" },
                fromSelf = event.selfId != 0L && event.userId == event.selfId,
                rawPayload = event,
            )
        }

        private fun extractMiniAppUrl(messageSegments: List<OneBot11MessageSegment>): String? {
            val jsonData = messageSegments
                .firstOrNull { it.type == "json" }
                ?.data
                ?.get("data")
                ?: return null

            val patterns = listOf(
                """"qqdocurl"\s*:\s*"([^"]+)"""".toRegex(),
                """"jumpUrl"\s*:\s*"([^"]+)"""".toRegex(),
                """"url"\s*:\s*"([^"]+bilibili[^"]+)"""".toRegex(),
                """(https?://[^\s"]+bilibili[^\s"]+)""".toRegex(),
            )

            return patterns.asSequence()
                .mapNotNull { regex -> regex.find(jsonData)?.groupValues?.getOrNull(1) }
                .map { value ->
                    value
                        .replace("\\/", "/")
                        .replace("&#44;", ",")
                }
                .firstOrNull()
        }

        /**
         * 原始 CQ 串只用于日志展示，不应回流到搜索文本；否则图片/回复等非文本消息会误触发链接解析。
         */
        private fun shouldUseRawMessageForSearch(rawMessage: String): Boolean {
            val normalized = rawMessage.trim()
            if (normalized.isBlank()) {
                return false
            }
            return !normalized.contains("[CQ:")
        }
    }
}
