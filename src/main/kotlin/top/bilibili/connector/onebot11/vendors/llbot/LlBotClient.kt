package top.bilibili.connector.onebot11.vendors.llbot

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import top.bilibili.config.NapCatConfig
import top.bilibili.connector.ConnectionBackoffPolicy
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.onebot11.core.OneBot11MessageEvent
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LlBotClient internal constructor(
    private val config: NapCatConfig,
    private val transport: LlBotTransport = KtorLlBotTransport(),
) {
    private val logger = LoggerFactory.getLogger(LlBotClient::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val connected = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectBackoffPolicy = ConnectionBackoffPolicy(
        baseDelayMillis = config.reconnectInterval.coerceAtLeast(1_000L),
        maxDelayMillis = maxOf(config.reconnectInterval, 60_000L),
    )
    private val sendMode = config.sendMode.lowercase()
    private val maxBase64ImageSizeBytes = 10L * 1024L * 1024L
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<LlBotResponse>>()
    private val inboundEvents = MutableSharedFlow<OneBot11MessageEvent>(replay = 0, extraBufferCapacity = 100)
    private var connectionJob: Job? = null
    private var session: LlBotGatewaySession? = null

    val eventFlow: Flow<OneBot11MessageEvent> = inboundEvents.asSharedFlow()

    /**
     * 启动 llbot vendor client，并在同一 client 内维护重连与消息分发循环。
     */
    fun start() {
        if (connectionJob?.isActive == true) {
            logger.info("llbot client 已在运行中")
            return
        }
        stopping.set(false)
        connectionJob = scope.launch {
            connectLoop()
        }
    }

    /**
     * 停止 llbot client，统一关闭会话、协程与传输资源。
     */
    suspend fun stop() {
        if (!stopping.compareAndSet(false, true)) {
            return
        }
        connected.set(false)
        runCatching {
            session?.close("llbot client stopping")
        }
        withTimeoutOrNull(5_000L) {
            connectionJob?.cancelAndJoin()
        }
        connectionJob = null
        session = null
        failPendingResponses("llbot client stopped")
        transport.close()
    }

    /**
     * 通过标准 OneBot11 send message action 发送群聊或私聊消息。
     */
    suspend fun sendMessage(
        chatType: PlatformChatType,
        targetId: Long,
        message: List<OneBot11MessageSegment>,
    ): Boolean {
        val targetKey = when (chatType) {
            PlatformChatType.GROUP -> "group_id"
            PlatformChatType.PRIVATE -> "user_id"
        }
        val action = when (chatType) {
            PlatformChatType.GROUP -> "send_group_msg"
            PlatformChatType.PRIVATE -> "send_private_msg"
        }
        val preparedMessage = prepareSegmentsForSend(message) ?: return false
        val response = sendActionAndAwaitResponse(
            action = action,
            params = mapOf(
                targetKey to JsonPrimitive(targetId),
                "message" to JsonArray(
                    preparedMessage.map { segment ->
                        JsonObject(
                            mapOf(
                                "type" to JsonPrimitive(segment.type),
                                "data" to JsonObject(
                                    segment.data.mapValues { (_, value) -> JsonPrimitive(value) },
                                ),
                            ),
                        )
                    },
                ),
            ),
            timeoutMillis = 20_000L,
        ) ?: return false
        return response.status == "ok" && response.retcode == 0
    }

    /**
     * 返回 llbot 当前连接状态与重连次数，供运行时监控和 capability guard 使用。
     */
    fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = connected.get(),
            reconnectAttempts = reconnectAttempts.get(),
        )
    }

    /**
     * 通过标准群列表 action 判断 Bot 当前是否仍在目标群中。
     */
    suspend fun isBotInGroup(groupId: Long): Boolean {
        val response = sendActionAndAwaitResponse(
            action = "get_group_list",
            timeoutMillis = 5_000L,
        ) ?: return false

        if (response.status != "ok" || response.retcode != 0) {
            logger.warn("llbot 查询群列表失败: status=${response.status}, retcode=${response.retcode}, msg=${response.message}")
            return false
        }

        val groups = response.data as? JsonArray ?: return false
        return groups.any { item ->
            val obj = item as? JsonObject ?: return@any false
            val id = (obj["group_id"] as? JsonPrimitive)?.content?.toLongOrNull()
            id == groupId
        }
    }

    /**
     * 通过登录信息与群成员角色查询判断当前群上下文是否允许 @全体。
     */
    suspend fun canAtAllInGroup(groupId: Long): Boolean {
        val selfId = getLoginInfo() ?: return false
        val response = sendActionAndAwaitResponse(
            action = "get_group_member_info",
            params = mapOf(
                "group_id" to JsonPrimitive(groupId),
                "user_id" to JsonPrimitive(selfId),
                "no_cache" to JsonPrimitive(true),
            ),
            timeoutMillis = 5_000L,
        ) ?: return false

        if (response.status != "ok" || response.retcode != 0) {
            logger.warn("llbot 查询群成员信息失败: status=${response.status}, retcode=${response.retcode}, msg=${response.message}")
            return false
        }

        val data = response.data as? JsonObject ?: return false
        val role = (data["role"] as? JsonPrimitive)?.content?.lowercase() ?: return false
        return role == "owner" || role == "admin"
    }

    /**
     * 统一执行 llbot 连接与重连循环，避免 manager 和 adapter 感知 vendor 会话细节。
     */
    private suspend fun connectLoop() {
        while (scope.coroutineContext[Job]?.isActive == true && !stopping.get()) {
            try {
                if (config.maxReconnectAttempts != -1 && reconnectAttempts.get() >= config.maxReconnectAttempts) {
                    logger.error("llbot 已达到最大重连次数 (${config.maxReconnectAttempts})")
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
                logger.warn("llbot 连接失败: ${e.message}")
                delay(reconnectBackoffPolicy.nextDelayMillis(reconnectAttempts.get()))
            }
        }
    }

    /**
     * 建立单次 llbot 网关会话，并在会话期间分发响应与消息事件。
     */
    private suspend fun connect() {
        val headers = buildMap {
            if (config.token.isNotBlank()) {
                put("Authorization", "Bearer ${config.token}")
            }
        }
        val gatewaySession = transport.openGateway(config.getWebSocketUrl(), headers)
        session = gatewaySession
        connected.set(true)
        reconnectAttempts.set(0)
        val collectorJob = scope.launch {
            gatewaySession.incoming.collect { payload ->
                handleInboundPayload(payload)
            }
        }
        try {
            val failure = gatewaySession.closeSignal.await()
            if (failure != null) {
                throw failure
            }
            if (!stopping.get()) {
                throw IllegalStateException("llbot gateway disconnected")
            }
        } finally {
            connected.set(false)
            session = null
            collectorJob.cancelAndJoin()
            failPendingResponses(if (stopping.get()) "llbot client stopping" else "llbot gateway disconnected")
        }
    }

    /**
     * 优先处理 API 响应，其次处理消息事件；未知帧直接忽略，避免污染平台事件流。
     */
    private suspend fun handleInboundPayload(payload: String) {
        val response = runCatching { llBotJson.decodeFromString<LlBotResponse>(payload) }.getOrNull()
        if (response?.echo != null) {
            pendingResponses.remove(response.echo)?.complete(response)
            return
        }

        val event = runCatching { llBotJson.decodeFromString<LlBotMessageEvent>(payload) }.getOrNull() ?: return
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

    /**
     * 发送标准 OneBot11 action 并按 echo 等待响应，供消息与能力探测逻辑统一复用。
     */
    private suspend fun sendActionAndAwaitResponse(
        action: String,
        params: Map<String, JsonElement> = emptyMap(),
        timeoutMillis: Long = 5_000L,
    ): LlBotResponse? {
        if (!connected.get()) {
            return null
        }
        val activeSession = session ?: return null
        val echo = "${action}_${System.currentTimeMillis()}"
        val deferred = CompletableDeferred<LlBotResponse>()
        pendingResponses[echo] = deferred
        return try {
            activeSession.sendText(
                llBotJson.encodeToString(
                    LlBotAction(
                        action = action,
                        params = params,
                        echo = echo,
                    ),
                ),
            )
            withTimeoutOrNull(timeoutMillis) {
                deferred.await()
            }
        } finally {
            pendingResponses.remove(echo)
        }
    }

    /**
     * 请求当前登录 Bot 的用户 ID，供 @全体 权限探测复用。
     */
    private suspend fun getLoginInfo(): Long? {
        val response = sendActionAndAwaitResponse(
            action = "get_login_info",
            timeoutMillis = 5_000L,
        ) ?: return null

        if (response.status != "ok" || response.retcode != 0) {
            logger.warn("llbot 获取登录信息失败: status=${response.status}, retcode=${response.retcode}, msg=${response.message}")
            return null
        }

        val data = response.data as? JsonObject ?: return null
        val selfIdPrimitive = data["user_id"] as? JsonPrimitive ?: return null
        return selfIdPrimitive.content.toLongOrNull()
    }

    /**
     * llbot 在发送前按当前 send_mode 处理图片段；base64 模式下优先把本地文件提升为 base64://。
     */
    private suspend fun prepareSegmentsForSend(
        segments: List<OneBot11MessageSegment>,
    ): List<OneBot11MessageSegment>? {
        if (sendMode != "base64") {
            return segments
        }

        val convertedSegments = mutableListOf<OneBot11MessageSegment>()
        for (segment in segments) {
            if (segment.type != "image") {
                convertedSegments += segment
                continue
            }

            val imageSource = segment.data["file"]
            if (imageSource.isNullOrBlank()) {
                logger.error("llbot 图片段缺少 file 字段，无法执行 base64 发送")
                return null
            }

            if (imageSource.startsWith("base64://")) {
                convertedSegments += segment
                continue
            }

            val localFile = resolveLocalFile(imageSource)
            if (localFile == null) {
                logger.error("llbot base64 转换失败，不支持的图片路径: $imageSource")
                return null
            }

            if (!localFile.exists() || !localFile.isFile) {
                logger.error("llbot base64 转换失败，图片文件不存在: ${localFile.absolutePath}")
                return null
            }

            val fileSize = localFile.length()
            if (fileSize > maxBase64ImageSizeBytes) {
                logger.error("llbot base64 转换失败，文件大小超过限制: ${localFile.name}, ${fileSize / 1024 / 1024}MB > 10MB")
                return null
            }

            val encoded = withContext(Dispatchers.IO) {
                Base64.getEncoder().encodeToString(localFile.readBytes())
            }
            convertedSegments += segment.copy(
                data = segment.data.toMutableMap().apply {
                    put("file", "base64://$encoded")
                },
            )
        }
        return convertedSegments
    }

    /**
     * 仅把本地路径或 file:// 视为可执行 base64 转换的来源，远程 URL 继续保持显式失败。
     */
    private fun resolveLocalFile(imageSource: String): File? {
        if (imageSource.startsWith("http://") || imageSource.startsWith("https://")) {
            return null
        }

        if (!imageSource.startsWith("file://")) {
            return File(imageSource)
        }

        return runCatching {
            Paths.get(URI(imageSource)).toFile()
        }.getOrElse {
            val normalized = imageSource.removePrefix("file:///").removePrefix("file://")
            File(normalized)
        }
    }

    /**
     * 在会话结束时统一失败未决请求，避免调用方挂起等待失联响应。
     */
    private fun failPendingResponses(reason: String) {
        val pending = pendingResponses.entries.toList()
        pendingResponses.clear()
        pending.forEach { (_, deferred) ->
            deferred.completeExceptionally(CancellationException(reason))
        }
    }
}

internal class LlBotAdapterTransport(
    private val llBotClient: LlBotClient,
) : top.bilibili.connector.onebot11.core.OneBot11Transport {
    override val eventFlow: Flow<OneBot11MessageEvent> = llBotClient.eventFlow

    /**
     * 将 adapter 生命周期桥接到 llbot vendor client，保持 manager 只感知统一 OneBot11 transport 契约。
     */
    override fun start() {
        llBotClient.start()
    }

    /**
     * 将 adapter 停机统一转发到 llbot client 的 suspend 生命周期。
     */
    override suspend fun stop() {
        llBotClient.stop()
    }

    /**
     * 将 OneBot11 标准消息发送委托到 llbot client，避免 manager 继续感知 vendor 细节。
     */
    override suspend fun sendMessage(
        chatType: PlatformChatType,
        targetId: Long,
        message: List<OneBot11MessageSegment>,
    ): Boolean {
        return llBotClient.sendMessage(chatType, targetId, message)
    }

    /**
     * 将 llbot 运行状态透传给统一平台运行时状态视图。
     */
    override fun runtimeStatus(): PlatformRuntimeStatus {
        return llBotClient.runtimeStatus()
    }
}
