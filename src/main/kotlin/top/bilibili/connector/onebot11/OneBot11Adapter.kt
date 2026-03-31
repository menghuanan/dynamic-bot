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
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import top.bilibili.connector.onebot11.core.OneBot11MessageEvent
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment
import top.bilibili.connector.onebot11.core.OneBot11Transport
import top.bilibili.utils.ImageCache
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

    /**
     * 启动底层 OneBot11 传输，保持平台层只面对统一适配器生命周期。
     */
    override fun start() {
        transport.start()
    }

    /**
     * 停止底层 OneBot11 传输，确保连接关闭与协程回收沿统一 suspend 生命周期执行。
     */
    override suspend fun stop() {
        transport.stop()
    }

    /**
     * 将平台无关消息片段映射为 OneBot11 段并按联系人类型发送，避免业务层继续处理数字 ID 与协议差异。
     */
    override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        if (contact.platform != PlatformType.ONEBOT11) return false
        val numericId = contact.id.toLongOrNull() ?: return false
        return transport.sendMessage(contact.type, numericId, toMessageSegments(message))
    }

    /**
     * 透传底层 OneBot11 传输运行状态，供平台层统一监控连接健康度。
     */
    override fun runtimeStatus(): PlatformRuntimeStatus {
        return transport.runtimeStatus()
    }

    /**
     * 透传底层 OneBot11 传输资源观测快照，供 manager 与 guardian 在平台层统一读取。
     */
    override fun runtimeObservability(): PlatformObservabilitySnapshot {
        return transport.runtimeObservability()
    }

    /**
     * 对群聊联系人走显式群可达性探测，对私聊默认按 OneBot11 常规语义视为可发送。
     */
    override suspend fun isContactReachable(contact: PlatformContact): Boolean {
        if (contact.platform != PlatformType.ONEBOT11) return false
        if (contact.type != PlatformChatType.GROUP) return true
        val groupId = contact.id.toLongOrNull() ?: return false
        return isGroupReachable(groupId)
    }

    /**
     * 仅在群聊上下文尝试判断 @全体 能力，避免私聊或跨平台联系人误入 vendor 权限探测。
     */
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

    /**
     * 将统一发送模型映射为 OneBot11 消息段，避免各 vendor 重复实现同一套基础转换。
     */
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

    /**
     * 统一解析图片来源到 OneBot11 `file` 字段，避免本地文件、远程地址与二进制输入走散。
     */
    private fun resolveImageFile(source: ImageSource): String {
        return when (source) {
            is ImageSource.LocalFile -> {
                if (source.path.startsWith("file://") || source.path.startsWith("base64://")) {
                    source.path
                } else {
                    ImageCache.toFileUrl(source.path)
                }
            }
            is ImageSource.RemoteUrl -> source.url
            is ImageSource.Binary -> encodeBinaryImage(source.bytes)
        }
    }

    /**
     * 统一生成 base64:// 图片载荷，避免 LocalFile 与 Binary 走出两套编码格式。
     */
    private fun encodeBinaryImage(bytes: ByteArray): String {
        val encoded = Base64.getEncoder().encodeToString(bytes)
        return "base64://$encoded"
    }

    companion object {
        /**
         * 将标准 OneBot11 消息事件归一化为平台无关的入站模型，供命令与监听链统一消费。
         */
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
                    // 只有纯文本 raw_message 才回流到搜索词，避免 CQ 码把图片/回复误识别成可搜索文本。
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

        /**
         * 从 OneBot11 的 json 段中提取小程序或卡片跳转链接，供上层链接解析链补足搜索文本。
         */
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
