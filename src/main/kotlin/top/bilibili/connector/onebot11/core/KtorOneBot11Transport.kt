package top.bilibili.connector.onebot11.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.ConnectionPool
import org.slf4j.LoggerFactory
import top.bilibili.config.NapCatConfig
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformRuntimeStatus
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class KtorOneBot11Transport(
    private val config: NapCatConfig,
) : OneBot11Transport {
    private val logger = LoggerFactory.getLogger(KtorOneBot11Transport::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeout
            requestTimeoutMillis = config.connectTimeout
        }
        install(WebSockets) {
            pingIntervalMillis = config.heartbeatInterval
        }
        engine {
            config {
                connectionPool(
                    ConnectionPool(
                        maxIdleConnections = 3,
                        keepAliveDuration = 3,
                        timeUnit = TimeUnit.MINUTES,
                    ),
                )
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connected = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private var connectionJob: Job? = null
    private var session: DefaultClientWebSocketSession? = null

    private val inboundEvents = MutableSharedFlow<OneBot11MessageEvent>(replay = 0, extraBufferCapacity = 100)
    override val eventFlow: Flow<OneBot11MessageEvent> = inboundEvents.asSharedFlow()

    override fun start() {
        if (connectionJob?.isActive == true) {
            logger.info("generic OneBot11 transport 已在运行中")
            return
        }
        stopping.set(false)
        connectionJob = scope.launch {
            connectLoop()
        }
    }

    override suspend fun stop() {
        if (!stopping.compareAndSet(false, true)) {
            return
        }
        connected.set(false)
        runCatching {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "generic onebot11 transport stopping"))
        }
        withTimeoutOrNull(5_000L) {
            connectionJob?.cancelAndJoin()
        }
        connectionJob = null
        session = null
        client.close()
    }

    /**
     * generic OneBot11 只负责协议级消息投递，不附带 NapCat 的响应确认或专有能力探测。
     */
    override suspend fun sendMessage(
        chatType: PlatformChatType,
        targetId: Long,
        message: List<OneBot11MessageSegment>,
    ): Boolean {
        if (!connected.get()) {
            return false
        }
        val action = when (chatType) {
            PlatformChatType.GROUP -> "send_group_msg"
            PlatformChatType.PRIVATE -> "send_private_msg"
        }
        val targetKey = when (chatType) {
            PlatformChatType.GROUP -> "group_id"
            PlatformChatType.PRIVATE -> "user_id"
        }
        val request = OutboundAction(
            action = action,
            params = mapOf(
                targetKey to JsonPrimitive(targetId),
                "message" to buildJsonArray {
                    message.forEach { segment ->
                        add(
                            buildJsonObject {
                                put("type", JsonPrimitive(segment.type))
                                put(
                                    "data",
                                    buildJsonObject {
                                        segment.data.forEach { (key, value) ->
                                            put(key, JsonPrimitive(value))
                                        }
                                    },
                                )
                            },
                        )
                    }
                },
            ),
            echo = null,
        )
        val activeSession = session ?: return false
        return runCatching {
            activeSession.send(Frame.Text(json.encodeToString(request)))
        }.isSuccess
    }

    override fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = connected.get(),
            reconnectAttempts = reconnectAttempts.get(),
        )
    }

    private suspend fun connectLoop() {
        while (scope.coroutineContext[Job]?.isActive == true && !stopping.get()) {
            try {
                if (config.maxReconnectAttempts != -1 && reconnectAttempts.get() >= config.maxReconnectAttempts) {
                    logger.error("generic OneBot11 已达到最大重连次数 (${config.maxReconnectAttempts})")
                    break
                }
                connect()
                reconnectAttempts.set(0)
            } catch (e: CancellationException) {
                if (stopping.get()) {
                    break
                }
                throw e
            } catch (e: Exception) {
                if (stopping.get()) {
                    break
                }
                reconnectAttempts.incrementAndGet()
                logger.warn("generic OneBot11 连接失败: ${e.message}")
                delay(config.reconnectInterval)
            }
        }
    }

    /**
     * 将协议层事件直接规范化为通用 OneBot11 模型，避免 generic 路径继续依赖 NapCat DTO。
     */
    private suspend fun connect() {
        client.webSocket(
            urlString = config.getWebSocketUrl(),
            request = {
                if (config.token.isNotBlank()) {
                    headers["Authorization"] = "Bearer ${config.token}"
                }
            },
        ) {
            session = this
            connected.set(true)
            for (frame in incoming) {
                if (frame !is Frame.Text) {
                    continue
                }
                handleFrame(frame.readText())
            }
        }
        connected.set(false)
        session = null
    }

    private suspend fun handleFrame(payload: String) {
        val event = runCatching { json.decodeFromString<InboundMessageEvent>(payload) }.getOrNull() ?: return
        if (event.postType != "message") {
            return
        }
        inboundEvents.emit(
            OneBot11MessageEvent(
                messageType = event.messageType,
                messageId = event.messageId,
                userId = event.userId,
                message = event.message.map { segment -> OneBot11MessageSegment(segment.type, segment.data) },
                rawMessage = event.rawMessage,
                groupId = event.groupId,
                selfId = event.selfId,
            ),
        )
    }

    @Serializable
    private data class InboundMessageEvent(
        @SerialName("post_type")
        val postType: String = "message",
        @SerialName("message_type")
        val messageType: String,
        @SerialName("message_id")
        val messageId: Int,
        @SerialName("user_id")
        val userId: Long,
        val message: List<SerializableMessageSegment>,
        @SerialName("raw_message")
        val rawMessage: String,
        @SerialName("group_id")
        val groupId: Long? = null,
        @SerialName("self_id")
        val selfId: Long = 0L,
    )

    @Serializable
    private data class SerializableMessageSegment(
        val type: String,
        val data: Map<String, String> = emptyMap(),
    )

    @Serializable
    private data class OutboundAction(
        val action: String,
        val params: Map<String, JsonElement> = emptyMap(),
        val echo: String? = null,
    )
}
