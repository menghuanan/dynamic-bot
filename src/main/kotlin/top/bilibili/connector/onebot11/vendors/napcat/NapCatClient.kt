package top.bilibili.connector.onebot11.vendors.napcat

import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import org.slf4j.LoggerFactory
import top.bilibili.connector.ConnectionBackoffPolicy
import top.bilibili.connector.PlatformHttpClientSnapshot
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.service.MessageLogSimplifier
import top.bilibili.config.NapCatConfig
import java.io.File
import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * NapCat OneBot v11 WebSocket 客户端（反向 WS）
 */
class NapCatClient(
    private val config: NapCatConfig
) {
    private val logger = LoggerFactory.getLogger(NapCatClient::class.java)
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }
    private val connectionPool = ConnectionPool(
        maxIdleConnections = 3,
        keepAliveDuration = 3,
        timeUnit = TimeUnit.MINUTES,
    )
    private val okHttpDispatcher = Dispatcher()

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = config.connectTimeout
            requestTimeoutMillis = config.connectTimeout
        }
        install(WebSockets) {
            pingIntervalMillis = config.heartbeatInterval
        }
        // ✅ P3修复: 配置连接池参数，避免空闲连接占用资源
        engine {
            config {
                dispatcher(okHttpDispatcher)
                connectionPool(connectionPool)
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isConnected = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectBackoffPolicy = ConnectionBackoffPolicy(
        baseDelayMillis = config.reconnectInterval.coerceAtLeast(1_000L),
        maxDelayMillis = maxOf(config.reconnectInterval, 60_000L),
    )
    private val livenessTimeoutMillis = maxOf(config.heartbeatInterval * 2, 60_000L)
    private val lastInboundAtMillis = AtomicLong(0L)

    private val _eventFlow = MutableSharedFlow<MessageEvent>(replay = 0, extraBufferCapacity = 100)
    val eventFlow = _eventFlow.asSharedFlow()

    private var session: DefaultClientWebSocketSession? = null
    private var connectionJob: Job? = null
    private var livenessWatchJob: Job? = null
    private val sendChannelCapacity = 200
    private val sendChannel = Channel<QueuedOutgoingPayload>(capacity = sendChannelCapacity)  // ✅ P1修复: 从1000降低到200，降低内存占用
    private val sendMode = config.sendMode.lowercase()
    private val maxBase64ImageSizeBytes = 10L * 1024L * 1024L
    private val pendingResponses = ConcurrentHashMap<String, CompletableDeferred<OneBotResponse>>()
    private val queuedMessageCount = AtomicInteger(0)

    // ✅ P3修复: 发送队列满载告警标志，避免重复告警
    private var sendQueueFullWarningLogged = false
    private val base64LogRegex = Regex("""base64://[A-Za-z0-9+/=]+""")

    private data class QueuedOutgoingPayload(
        val rawJson: String,
        val logPreview: String,
    )

    /** Bot 的 QQ 号 */
    var selfId: Long = 0L
        private set

    /**
     * ✅ P3修复: 检查发送队列是否已满（供 ProcessGuardian 调用）
     */
    fun isSendQueueFull(): Boolean {
        return queuedMessageCount.get() >= sendChannelCapacity
    }

    /** 启动客户端 */
    /**
     * 启动 NapCat 长连接与重连循环，供适配器层统一接通 vendor client 生命周期。
     */
    fun start() {
        if (connectionJob?.isActive == true) {
            logger.info("NapCat WebSocket 客户端已在运行中，忽略重复启动")
            return
        }
        stopping.set(false)
        logger.info("正在启动 NapCat WebSocket 客户端...")
        logger.info("目标地址: ${config.getWebSocketUrl()}")
        connectionJob = scope.launch {
            connectLoop()
        }
    }

    /** 停止客户端 */
    /**
     * 停止 NapCat client 并回收会话、队列和协程资源，避免停机后残留后台循环。
     */
    suspend fun stop() {
        if (!stopping.compareAndSet(false, true)) {
            logger.info("NapCat WebSocket 客户端已在停止中")
            return
        }
        stopping.set(true)
        logger.info("正在停止 NapCat WebSocket 客户端...")

        // 标记为未连接状态
        isConnected.set(false)
        try {
            session?.close(CloseReason(CloseReason.Codes.NORMAL, "客户端停止"))
            withTimeoutOrNull(5_000L) {
                livenessWatchJob?.cancelAndJoin()
            } ?: logger.warn("等待 NapCat 存活检测协程结束超时，继续执行停机")
        } catch (e: Exception) {
            logger.warn("关闭 WebSocket 会话失败", e)
        }

        // 关闭发送通道（触发发送协程退出）
        sendChannel.close()

        // 显式等待连接循环退出，避免重复 start 期间叠出第二条长期重连协程。
        withTimeoutOrNull(5_000L) {
            connectionJob?.cancelAndJoin()
        }
        connectionJob = null

        // ✅ 挂起等待协程结束（带超时），避免在停机链路里重新桥接回阻塞式关闭。
        withTimeoutOrNull(5_000L) {
            scope.coroutineContext[Job]?.cancelAndJoin()
            logger.info("NapCat 协程已正常结束")
        } ?: logger.warn("等待 NapCat 协程结束超时，强制取消")

        // 关闭 WebSocket 会话
        failPendingResponses("NapCat 客户端已停止")

        // 关闭 HTTP 客户端
        client.close()
        connectionPool.evictAll()
        okHttpDispatcher.executorService.shutdown()

        logger.info("NapCat WebSocket 客户端已停止")
    }

    /** 连接循环（支持自动重连） */
    /**
     * 统一维护 NapCat 的连接与退避重试，避免上层生命周期代码直接感知 vendor 重连细节。
     */
    private suspend fun connectLoop() {
        while (scope.isActive && !stopping.get()) {
            try {
                if (config.maxReconnectAttempts != -1 &&
                    reconnectAttempts.get() >= config.maxReconnectAttempts
                ) {
                    logger.error("已达到最大重连次数 (${config.maxReconnectAttempts})，停止重连")
                    break
                }

                connect()

            } catch (e: CancellationException) {
                if (stopping.get() || !scope.isActive) {
                    logger.info("停机期间停止 NapCat 重连循环")
                    break
                }
                throw e
            } catch (e: Exception) {
                if (stopping.get()) {
                    logger.info("停机期间停止 NapCat 重连循环")
                    break
                }
                logger.error("WebSocket 连接失败: ${e.message}")
                reconnectAttempts.incrementAndGet()

                if (config.maxReconnectAttempts == -1 ||
                    reconnectAttempts.get() < config.maxReconnectAttempts
                ) {
                    val backoffDelay = reconnectBackoffPolicy.nextDelayMillis(reconnectAttempts.get())
                    logger.info("将在 ${backoffDelay}ms 后重连...")
                    delay(backoffDelay)
                }
            }
        }
    }

    /** 建立 WebSocket 连接 */
    /**
     * 建立单次 NapCat 会话，并把登录态探测、收发循环与存活监控收口在同一连接代际里。
     */
    private suspend fun connect() {
        logger.info("正在连接到 NapCat WebSocket 服务器...")

        client.webSocket(
            urlString = config.getWebSocketUrl(),
            request = {
                if (config.token.isNotBlank()) {
                    headers["Authorization"] = "Bearer ${config.token}"
                }
            }
        ) {
            session = this
            isConnected.set(true)
            reconnectAttempts.set(0)
            lastInboundAtMillis.set(System.currentTimeMillis())
            livenessWatchJob?.cancel()
            livenessWatchJob = launch {
                runLivenessWatch()
            }
            logger.info("WebSocket 连接成功!")

            // 获取 Bot 的 QQ 号
            launch {
                try {
                    // 等首轮握手稳定后再拉取 selfId，避免把刚连上的瞬时波动误判成登录失败。
                    delay(500) // 等待连接稳定
                    val loginInfo = getLoginInfo()
                    if (loginInfo != null) {
                        selfId = loginInfo
                        logger.info("Bot QQ 号: $selfId")
                    }
                } catch (e: Exception) {
                    if (stopping.get() || e is CancellationException) {
                        logger.info("停机期间忽略登录信息获取结果: ${e.message}")
                    } else {
                        logger.error("获取登录信息失败: ${e.message}", e)
                    }
                }
            }

            // 启动发送协程
            val sendJob = launch {
                for (payload in sendChannel) {
                    // 发送前先扣减排队计数，避免阻塞发送时外部背压指标长期高估。
                    queuedMessageCount.updateAndGet { count -> if (count > 0) count - 1 else 0 }
                    try {
                        send(payload.rawJson)
                        logger.debug("已发送消息: ${payload.logPreview}")
                    } catch (e: CancellationException) {
                        if (stopping.get() || !scope.isActive) {
                            logger.info("停机期间停止 NapCat 发送循环")
                            break
                        }
                        throw e
                    } catch (e: Exception) {
                        if (stopping.get()) {
                            logger.info("停机期间停止 NapCat 发送循环: ${e.message}")
                            break
                        }
                        logger.error("发送消息失败: ${e.message}", e)
                    }
                }
            }

            try {
                // 接收消息循环
                for (frame in incoming) {
                    lastInboundAtMillis.set(System.currentTimeMillis())
                    when (frame) {
                        is Frame.Text -> {
                            val text = frame.readText()
                            logger.debug("收到消息: $text")
                            handleMessage(text)
                        }

                        is Frame.Close -> {
                            if (stopping.get()) {
                                logger.info("停机期间 WebSocket 连接已关闭: ${frame.readReason()}")
                            } else {
                                logger.warn("WebSocket 连接被关闭: ${frame.readReason()}")
                            }
                            break
                        }

                        else -> {
                            logger.debug("收到其他类型的帧: ${frame.frameType}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                if (stopping.get() || !scope.isActive) {
                    logger.info("停机期间停止 NapCat 接收循环")
                } else {
                    throw e
                }
            } catch (e: Exception) {
                if (stopping.get()) {
                    logger.info("停机期间停止 NapCat 接收循环: ${e.message}")
                } else {
                    logger.error("接收消息时发生错误: ${e.message}", e)
                }
            } finally {
                isConnected.set(false)
                livenessWatchJob?.cancel()
                livenessWatchJob = null
                sendJob.cancel()
                session = null
                failPendingResponses(if (stopping.get()) "NapCat 客户端已停止" else "WebSocket 已断开")
                logger.info("WebSocket 连接已断开")
            }
        }
    }

    /**
     * 应用层存活探测独立于底层 ping/pong；超过阈值未收到任何帧时主动触发重连。
     */
    private suspend fun runLivenessWatch() {
        val probeIntervalMillis = (config.heartbeatInterval / 2).coerceIn(5_000L, 15_000L)
        while (scope.isActive && !stopping.get()) {
            delay(probeIntervalMillis)
            if (!isConnected.get()) {
                continue
            }
            val inactivityMillis = System.currentTimeMillis() - lastInboundAtMillis.get()
            if (inactivityMillis < livenessTimeoutMillis) {
                continue
            }
            logger.warn("NapCat liveness timeout: ${inactivityMillis}ms 未收到入站消息，主动关闭连接以触发重连")
            session?.close(CloseReason(CloseReason.Codes.GOING_AWAY, "liveness-timeout"))
            return
        }
    }

    /** 简化消息内容用于日志显示 */
    /** Incoming message simplification for logs. */
    internal fun simplifyIncomingMessageForLog(rawMessage: String): String {
        return MessageLogSimplifier.simplifyIncomingRaw(rawMessage) { length ->
            logger.warn("消息过长 ($length 字符)，截断处理")
        }
    }

    /** 处理收到的消息 */
    /**
     * 先按响应帧匹配挂起请求，再按事件类型尝试解析入站消息，避免一条帧被多套逻辑重复消费。
     */
    private suspend fun handleMessage(text: String) {
        // 优先处理 API 响应，避免被后续消息事件分支吞掉
        try {
            val response = json.decodeFromString<OneBotResponse>(text)
            val responseEcho = response.echo
            if (!responseEcho.isNullOrBlank()) {
                pendingResponses.remove(responseEcho)?.complete(response)
            }
            logger.debug("收到 API 响应: echo=${responseEcho ?: "null"}, retcode=${response.retcode}")
            return
        } catch (_: Exception) {
            // ignore
        }

        try {
            logger.debug("收到原始消息: $text")

            // 尝试解析为消息事件
            val event = json.decodeFromString<MessageEvent>(text)
            val simplifiedMessage = simplifyIncomingMessageForLog(event.rawMessage)
            logger.debug("成功解析 ${event.messageType} 消息事件 [${event.messageId}] 来自 ${event.userId}: $simplifiedMessage")
            _eventFlow.emit(event)
        } catch (e: Exception) {
            logger.debug("不是消息事件，尝试解析为其他类型: ${e.message}")
            // 如果不是消息事件，尝试其他类型
            try {
                val metaEvent = json.decodeFromString<MetaEvent>(text)
                if (metaEvent.metaEventType == "heartbeat") {
                    logger.debug("收到心跳包")
                } else {
                    logger.debug("收到元事件: ${metaEvent.metaEventType}")
                }
            } catch (e2: Exception) {
                logger.debug("收到未知类型的消息，忽略: ${e2.message}")
            }
        }
    }

    /** 发送群消息 */
    /**
     * 通过 NapCat 标准群消息 action 发送消息段，保持群聊发送与私聊发送共用同一底层路径。
     */
    suspend fun sendGroupMessage(groupId: Long, message: List<MessageSegment>): Boolean {
        return sendMessage(
            action = "send_group_msg",
            targetKey = "group_id",
            targetId = groupId,
            message = message,
        )
    }

    /** 发送私聊消息 */
    /**
     * 通过 NapCat 标准私聊 action 发送消息段，保持私聊发送与群聊发送共用同一底层路径。
     */
    suspend fun sendPrivateMessage(userId: Long, message: List<MessageSegment>): Boolean {
        return sendMessage(
            action = "send_private_msg",
            targetKey = "user_id",
            targetId = userId,
            message = message,
        )
    }

    /** 通用发送消息方法 */
    /**
     * 统一组装 OneBot action、等待响应并处理降级日志，避免群聊与私聊各自复制发送细节。
     */
    private suspend fun sendMessage(
        action: String,
        targetKey: String,
        targetId: Long,
        message: List<MessageSegment>,
    ): Boolean {
        if (!isConnected.get()) {
            if (stopping.get()) {
                logger.info("停机期间跳过发送消息: action=$action")
            } else {
                logger.warn("WebSocket 未连接，无法发送消息")
            }
            return false
        }

        return try {
            val segmentsToSend = prepareSegmentsForSend(message) ?: return false
            val outgoingLogPreview = buildOutgoingLogPreview(segmentsToSend)
            val messageJson = if (config.messageFormat == "string" || config.messageFormat == "cqcode") {
                // NapCat 在字符串模式下要求发送 CQ 码文本，否则会把结构化段当作非法请求处理。
                JsonPrimitive(segmentsToSend.toCqCode())
            } else {
                json.encodeToJsonElement(segmentsToSend)
            }
            val paramsJson = buildMap<String, JsonElement> {
                put(targetKey, JsonPrimitive(targetId))
                put("message", messageJson)
            }

            val request = OneBotAction(
                action = action,
                params = paramsJson,
                echo = System.currentTimeMillis().toString()
            )

            val response = sendActionAndAwaitResponse(
                action = request.action,
                params = request.params,
                logPreview = outgoingLogPreview,
                timeoutMillis = 20_000L
            )

            if (response == null) {
                logger.warn("消息发送超时: action=$action")
                return false
            }
            if (response.status != "ok" || response.retcode != 0) {
                logger.warn("消息发送失败: action=$action, status=${response.status}, retcode=${response.retcode}, msg=${response.message}")
                return false
            }

            logger.debug("消息发送成功: action=$action")
            true
        } catch (e: Exception) {
            if (stopping.get() || e is CancellationException) {
                logger.info("停机期间忽略发送消息结果: action=$action, reason=${e.message}")
            } else {
                logger.error("发送消息失败: ${e.message}", e)
            }
            false
        }
    }

    internal fun buildOutgoingLogPreview(segments: List<MessageSegment>): String {
        return MessageLogSimplifier.simplifySegments(segments)
    }

    internal fun simplifyOutgoingMessageForLog(rawJson: String): String {
        val masked = base64LogRegex.replace(rawJson) { match ->
            val payload = match.value.removePrefix("base64://")
            if (payload.length <= 24) {
                "base64://$payload"
            } else {
                "base64://${payload.take(10)}...${payload.takeLast(6)}(len=${payload.length})"
            }
        }

        return if (masked.length > 800) {
            masked.take(800) + "...(truncated)"
        } else {
            masked
        }
    }

    /**
     * 统一把待发 JSON 放入发送队列，并在队列打满时只告警一次避免刷屏。
     */
    private suspend fun enqueueOutgoingJson(payload: String, logPreview: String? = null) {
        val queuedPayload = QueuedOutgoingPayload(
            rawJson = payload,
            logPreview = logPreview ?: simplifyOutgoingMessageForLog(payload),
        )
        val result = sendChannel.trySend(queuedPayload)
        if (result.isSuccess) {
            queuedMessageCount.incrementAndGet()
            sendQueueFullWarningLogged = false
            return
        }

        if (!sendQueueFullWarningLogged) {
            logger.warn("发送队列已满 (容量: $sendChannelCapacity)，消息可能延迟发送")
            sendQueueFullWarningLogged = true
        }

        sendChannel.send(queuedPayload)
        queuedMessageCount.incrementAndGet()
    }

    /**
     * 在连接终止时统一唤醒所有等待中的请求，避免调用方永久挂起在已失效的 echo 上。
     */
    private fun failPendingResponses(reason: String) {
        val pending = pendingResponses.entries.toList()
        pendingResponses.clear()
        pending.forEach { (_, deferred) ->
            deferred.completeExceptionally(CancellationException(reason))
        }
    }

    /**
     * 发送标准 OneBot action 并按 echo 等待响应，供发送链和能力探测链共享请求入口。
     */
    private suspend fun sendActionAndAwaitResponse(
        action: String,
        params: Map<String, JsonElement> = emptyMap(),
        logPreview: String? = null,
        timeoutMillis: Long = 5_000L
    ): OneBotResponse? {
        if (!isConnected.get()) {
            if (stopping.get()) {
                logger.info("停机期间跳过请求: action=$action")
            } else {
                logger.warn("WebSocket 未连接，无法请求 action=$action")
            }
            return null
        }

        val echo = "${action}_${System.currentTimeMillis()}"
        val deferred = CompletableDeferred<OneBotResponse>()
        pendingResponses[echo] = deferred

        return try {
            val request = OneBotAction(action = action, params = params, echo = echo)
            enqueueOutgoingJson(json.encodeToString(request), logPreview)
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            pendingResponses.remove(echo)
        }
    }

    /**
     * 按当前发送模式预处理图片段，确保 base64 模式下只把可验证的本地文件转成内联载荷。
     */
    private suspend fun prepareSegmentsForSend(segments: List<MessageSegment>): List<MessageSegment>? {
        if (sendMode != "base64") {
            logger.debug("图片发送模式: file")
            return segments
        }

        logger.debug("图片发送模式: base64，开始处理消息段")
        val convertedSegments = mutableListOf<MessageSegment>()
        var convertedCount = 0

        for (segment in segments) {
            if (segment.type != "image") {
                convertedSegments.add(segment)
                continue
            }

            val imageSource = segment.data["file"]
            if (imageSource.isNullOrBlank()) {
                logger.error("图片段缺少 file 字段，无法执行 base64 发送")
                return null
            }

            if (imageSource.startsWith("base64://")) {
                logger.warn("图片段已是 base64 格式，跳过重复转换")
                convertedSegments.add(segment)
                continue
            }

            val localFile = resolveLocalFile(imageSource)
            if (localFile == null) {
                logger.error("base64 转换失败，不支持的图片路径: $imageSource")
                return null
            }

            if (!localFile.exists() || !localFile.isFile) {
                logger.error("base64 转换失败，图片文件不存在: ${localFile.absolutePath}")
                return null
            }

            val fileSize = localFile.length()
            if (fileSize > maxBase64ImageSizeBytes) {
                logger.error("文件大小超过限制: ${localFile.name}, ${fileSize / 1024 / 1024}MB > 10MB")
                return null
            }

            val encoded = withContext(Dispatchers.IO) {
                Base64.getEncoder().encodeToString(localFile.readBytes())
            }

            val newData = segment.data.toMutableMap()
            newData["file"] = "base64://$encoded"
            convertedSegments.add(segment.copy(data = newData))
            convertedCount++
            logger.info("转化base64成功: ${localFile.name}")
        }

        logger.debug("base64 图片转换完成，转换数量: $convertedCount")
        return convertedSegments
    }

    /**
     * 仅接受本地路径或 file:// 作为 base64 转换来源，避免远程 URL 被误当成可读本地文件。
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
     * 在字符串发送模式下把消息段编码为 CQ 码文本，兼容 NapCat 的旧格式接口。
     */
    private fun List<MessageSegment>.toCqCode(): String {
        return joinToString("") { segment ->
            if (segment.type == "text") {
                // 转义 CQ 码特殊字符
                segment.data["text"]?.replace("&", "&amp;")
                    ?.replace("[", "&#91;")
                    ?.replace("]", "&#93;") ?: ""
            } else {
                val params = segment.data.entries.joinToString(",") { (k, v) ->
                    val value = v.replace("&", "&amp;")
                        .replace("[", "&#91;")
                        .replace("]", "&#93;")
                        .replace(",", "&#44;")
                    "$k=$value"
                }
                "[CQ:${segment.type},$params]"
            }
        }
    }

    /** 获取连接状态 */
    /**
     * 返回 NapCat 当前连接状态，供适配器与运行时状态汇总复用。
     */
    fun isConnected(): Boolean = isConnected.get()

    /** 获取重连次数 */
    /**
     * 返回当前已累积的重连次数，供监控与退避观察使用。
     */
    fun getReconnectAttempts(): Int = reconnectAttempts.get()

    /**
     * 导出 NapCat 当前底层 OkHttp 连接池、调度器与 WebSocket 会话活跃态，供平台守护统一汇总。
     */
    fun runtimeObservability(): PlatformObservabilitySnapshot {
        return PlatformObservabilitySnapshot(
            clients = listOf(
                PlatformHttpClientSnapshot(
                    adapterName = "onebot11",
                    transportName = "napcat",
                    connectionCount = connectionPool.connectionCount(),
                    idleConnectionCount = connectionPool.idleConnectionCount(),
                    queuedCallsCount = okHttpDispatcher.queuedCallsCount(),
                    runningCallsCount = okHttpDispatcher.runningCallsCount(),
                    webSocketSessionActive = session != null && isConnected.get(),
                ),
            ),
        )
    }

    /**
     * 通过群列表查询确认 Bot 当前是否仍在目标群中，避免把发送失败误当成群不可达。
     */
    suspend fun isBotInGroup(groupId: Long): Boolean {
        val response = sendActionAndAwaitResponse(
            action = "get_group_list",
            timeoutMillis = 5_000L
        ) ?: return false

        if (response.status != "ok" || response.retcode != 0) {
            logger.warn("查询群列表失败: status=${response.status}, retcode=${response.retcode}, msg=${response.message}")
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
            timeoutMillis = 5_000L
        ) ?: return false

        if (response.status != "ok" || response.retcode != 0) {
            logger.warn("查询群成员信息失败: status=${response.status}, retcode=${response.retcode}, msg=${response.message}")
            return false
        }

        val data = response.data as? JsonObject ?: return false
        val role = (data["role"] as? JsonPrimitive)?.content?.lowercase() ?: return false
        return role == "owner" || role == "admin"
    }

    /** 获取登录信息（Bot QQ 号） */
    /**
     * 请求当前登录 Bot 的 QQ 号，供权限探测和自消息识别复用。
     */
    private suspend fun getLoginInfo(): Long? {
        if (!isConnected.get()) {
            if (stopping.get()) {
                logger.info("停机期间跳过获取登录信息请求")
            } else {
                logger.warn("WebSocket 未连接，无法获取登录信息")
            }
            return null
        }

        return try {
            val response = sendActionAndAwaitResponse(
                action = "get_login_info",
                timeoutMillis = 5_000L
            ) ?: run {
                logger.warn("请求登录信息超时")
                return null
            }

            if (response.status != "ok" || response.retcode != 0) {
                logger.warn("请求登录信息失败: status=${response.status}, retcode=${response.retcode}, msg=${response.message}")
                return null
            }

            val data = response.data as? JsonObject ?: return null
            val selfIdPrimitive = data["user_id"] as? JsonPrimitive ?: return null
            selfIdPrimitive.content.toLongOrNull()
        } catch (e: Exception) {
            if (stopping.get() || e is CancellationException) {
                logger.info("停机期间忽略获取登录信息失败: ${e.message}")
            } else {
                logger.error("获取登录信息失败: ${e.message}", e)
            }
            null
        }
    }
}
