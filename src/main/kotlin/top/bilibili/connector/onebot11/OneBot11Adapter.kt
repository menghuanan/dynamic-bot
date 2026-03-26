package top.bilibili.connector.onebot11

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import top.bilibili.core.ContactId
import top.bilibili.napcat.MessageEvent
import top.bilibili.napcat.MessageSegment
import top.bilibili.napcat.NapCatClient
import top.bilibili.utils.ImageCache
import java.util.Base64

class OneBot11Adapter(
    private val napCatClient: NapCatClient,
) : PlatformAdapter {
    override val eventFlow: Flow<PlatformInboundMessage> =
        napCatClient.eventFlow.map(::normalize)

    override fun start() {
        napCatClient.start()
    }

    override fun stop() {
        napCatClient.stop()
    }

    override suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean {
        return napCatClient.sendGroupMessage(groupId, toMessageSegments(message))
    }

    override suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean {
        return napCatClient.sendPrivateMessage(userId, toMessageSegments(message))
    }

    override suspend fun sendMessage(contact: ContactId, message: List<OutgoingPart>): Boolean {
        return when (contact.type) {
            "group" -> sendGroupMessage(contact.id, message)
            "private" -> sendPrivateMessage(contact.id, message)
            else -> false
        }
    }

    override fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = napCatClient.isConnected(),
            reconnectAttempts = napCatClient.getReconnectAttempts(),
            sendQueueFull = napCatClient.isSendQueueFull(),
        )
    }

    override suspend fun isGroupReachable(groupId: Long): Boolean {
        return napCatClient.isBotInGroup(groupId)
    }

    override suspend fun canAtAll(groupId: Long): Boolean {
        return napCatClient.canAtAllInGroup(groupId)
    }

    private fun toMessageSegments(parts: List<OutgoingPart>): List<MessageSegment> {
        return parts.map { part ->
            when (part) {
                is OutgoingPart.Text -> MessageSegment.text(part.text)
                is OutgoingPart.Image -> MessageSegment.image(resolveImageFile(part.source))
                is OutgoingPart.MentionAll -> MessageSegment.atAll()
                is OutgoingPart.Reply -> MessageSegment.reply(part.messageId)
            }
        }
    }

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
            is ImageSource.Binary -> {
                val encoded = Base64.getEncoder().encodeToString(source.bytes)
                "base64://$encoded"
            }
        }
    }

    companion object {
        fun normalize(event: MessageEvent): PlatformInboundMessage {
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
                } else if (event.rawMessage.isNotBlank()) {
                    add(event.rawMessage.trim())
                }
                extractMiniAppUrl(event.message)?.let(::add)
            }.distinct()

            return PlatformInboundMessage(
                platform = PlatformType.ONEBOT11,
                chatType = chatType,
                chatId = chatId,
                senderId = event.userId.toString(),
                selfId = event.selfId.toString(),
                messageText = event.rawMessage,
                searchTexts = searchTexts,
                hasMention = event.message.any { it.type == "at" },
                fromSelf = event.selfId != 0L && event.userId == event.selfId,
                rawPayload = event,
            )
        }

        private fun extractMiniAppUrl(messageSegments: List<MessageSegment>): String? {
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
    }
}
