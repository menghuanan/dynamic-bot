package top.bilibili.connector.qqofficial

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.collect
import org.slf4j.LoggerFactory
import top.bilibili.config.QQOfficialConfig
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.ConnectionBackoffPolicy
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import top.bilibili.utils.toSubject
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal class QQOfficialAdapter(
    private val config: QQOfficialConfig = QQOfficialConfig(),
    internal val transport: QQOfficialTransport = KtorQQOfficialTransport(),
    private val imageUrlResolver: suspend (ImageSource) -> String? = ::defaultImageUrlResolver,
    private val currentTimeMillis: () -> Long = { System.currentTimeMillis() },
    internal val reachableContactTtlMillis: Long = DEFAULT_REACHABLE_CONTACT_TTL_MILLIS,
    internal val reachableContactsMaxSize: Int = DEFAULT_REACHABLE_CONTACTS_MAX_SIZE,
) : PlatformAdapter {
    private val logger = LoggerFactory.getLogger(QQOfficialAdapter::class.java)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val authMutex = Mutex()
    private val reachableContactsMutex = Mutex()
    private val connected = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val reconnectGuard = AtomicBoolean(false)
    private val reconnectAttempts = AtomicInteger(0)
    private val reconnectBackoffPolicy = ConnectionBackoffPolicy(
        baseDelayMillis = 3_000L,
        maxDelayMillis = 60_000L,
    )
    private val inboundPressureActive = AtomicBoolean(false)
    private val inboundDroppedEvents = AtomicInteger(0)
    private val effectiveReachableContactTtlMillis = reachableContactTtlMillis.coerceAtLeast(1L)
    private val effectiveReachableContactsMaxSize = reachableContactsMaxSize.coerceAtLeast(1)
    private val reachableContacts = LinkedHashMap<String, Long>()
    private val _eventFlow = MutableSharedFlow<PlatformInboundMessage>(replay = 0, extraBufferCapacity = 64)
    private var gatewaySession: QQOfficialGatewaySession? = null
    private var gatewayCollectJob: Job? = null
    private var gatewayCloseWatchJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private var readySignal: CompletableDeferred<Unit>? = null
    private var accessToken: String? = null
    private var accessTokenExpireAtMillis: Long = 0L
    private var sessionId: String? = null
    private var lastSeq: Int? = null
    private var selfOpenId: String = ""

    override val eventFlow: Flow<PlatformInboundMessage> = _eventFlow.asSharedFlow()

    /**
     * QQ 官方显式声明仅支持基础发送/图片/回复能力，@全体等特性继续通过 guard 走降级或停止。
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
     * QQ 官方对 @全体与非公网图片给出显式 guard reason，避免上层只能收到模糊的 false。
     */
    override suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        if (request.capability == PlatformCapability.AT_ALL) {
            return CapabilityGuardResult.Unsupported(reason = "QQ Official does not support @全体")
        }
        if (
            request.capability == PlatformCapability.SEND_IMAGES &&
            request.images.any { image -> image is ImageSource.LocalFile || image is ImageSource.Binary }
        ) {
            return CapabilityGuardResult.Degraded(reason = "QQ Official only supports public remote image URLs")
        }
        return super.guardCapability(request)
    }

    /**
     * 启动 QQ 官方网关并等待首轮握手完成，避免外层把仅创建对象误判为已可用连接。
     */
    override fun start() {
        if (!started.compareAndSet(false, true)) {
            logger.info("QQ 官方适配器已在运行中，忽略重复启动")
            return
        }
        try {
            check(hasCredentials()) { "QQ 官方平台缺少 app_id 或 app_secret，无法启动" }

            // 启动阶段等待首轮 Hello/Ready 完成，避免外层把“骨架启动”误判成“平台可用”。
            runBlocking {
                connectGateway(initialBootstrap = true)
            }
            logger.info("QQ 官方适配器已完成首轮网关握手")
        } catch (throwable: Throwable) {
            started.set(false)
            connected.set(false)
            throw throwable
        }
    }

    /**
     * 停止 QQ 官方适配器并回收网关、缓存与传输资源，避免退场后保留旧会话状态。
     */
    override suspend fun stop() {
        if (!started.compareAndSet(true, false)) {
            return
        }
        connected.set(false)
        reconnectJob?.cancelAndJoin()
        heartbeatJob?.cancelAndJoin()
        gatewayCloseWatchJob?.cancelAndJoin()
        gatewayCollectJob?.cancelAndJoin()
        gatewaySession?.close("adapter stop")
        clearReachableContacts()
        scope.cancel()
        transport.close()
    }

    /**
     * 按 QQ 官方平台约束执行发送，并在图片或 @全体 不可用时显式走失败或文本降级。
     */
    override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        if (contact.platform != PlatformType.QQ_OFFICIAL) return false
        if (!started.get() || !connected.get()) return false
        if (!canSendMessage(contact)) {
            logger.warn("QQ 官方联系人当前不可达，跳过发送: {}", contact.toSubject())
            return false
        }

        return runCatching {
            val sendPlan = buildSendPlan(message)
            if (sendPlan.unsupportedAtAll) {
                logger.warn("QQ 官方平台不支持 @全体，要求上层触发显式降级: {}", contact.toSubject())
                return false
            }
            if (sendPlan.replyId != null && !canReply(contact)) {
                logger.warn("QQ 官方联系人当前不支持回复消息: {}", contact.toSubject())
                return false
            }

            if (sendPlan.images.isEmpty()) {
                if (sendPlan.content.isBlank()) {
                    return false
                }
                postMessage(contact, msgType = 0, content = sendPlan.content, media = null, replyId = sendPlan.replyId)
                return true
            }

            val supportsDirectImage = canSendImages(contact, sendPlan.images)
            val resolvedImageUrls = if (supportsDirectImage) resolveImageUrls(sendPlan.images) else emptyList()
            if (resolvedImageUrls.isEmpty()) {
                if (sendPlan.content.isBlank()) {
                    logger.warn("QQ 官方平台当前无法发送本地/二进制图片，且消息无可降级文本: {}", contact.toSubject())
                    return false
                }
                // QQ 官方对非公网图片没有兜底上传能力，因此这里只能退回纯文本而不是伪造空 media 请求。
                logger.warn("QQ 官方平台当前仅直接支持公网图片 URL，已降级为纯文本发送: {}", contact.toSubject())
                postMessage(contact, msgType = 0, content = sendPlan.content, media = null, replyId = sendPlan.replyId)
                return true
            }

            resolvedImageUrls.forEachIndexed { index, imageUrl ->
                val media = uploadMedia(contact, imageUrl)
                // 文本和回复只放在第一条图片消息里，避免多图拆分后把同一段内容重复发给用户。
                val content = if (index == 0) sendPlan.content else ""
                postMessage(
                    contact = contact,
                    msgType = 7,
                    content = content,
                    media = media,
                    replyId = if (index == 0) sendPlan.replyId else null,
                )
            }
            true
        }.onFailure {
            logger.error("QQ 官方发送失败: ${contact.toSubject()} - ${it.message}", it)
        }.getOrDefault(false)
    }

    /**
     * 汇总 QQ 官方当前连接态、重连次数与入站背压状态，供运行时监控统一读取。
     */
    override fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = connected.get(),
            reconnectAttempts = reconnectAttempts.get(),
            inboundPressureActive = inboundPressureActive.get(),
            inboundDroppedEvents = inboundDroppedEvents.get(),
        )
    }

    // QQ 官方的群聊/私聊可达性依赖运行时已接受的会话，避免业务层误判“所有 openid 都可主动发送”。
    /**
     * 仅把运行期已建立过会话的联系人视为可达，避免对任意 openid 盲目尝试主动发送。
     */
    override suspend fun isContactReachable(contact: PlatformContact): Boolean {
        if (contact.platform != PlatformType.QQ_OFFICIAL) return false
        if (!connected.get()) return false
        return reachableContactsMutex.withLock {
            pruneReachableContactsLocked(currentTimeMillis())
            reachableContacts.containsKey(contact.toSubject())
        }
    }

    /**
     * QQ 官方主动发送仍依赖运行时会话可达性，这里显式暴露业务层使用的发送能力判断。
     */
    override suspend fun canSendMessage(contact: PlatformContact): Boolean {
        return isContactReachable(contact)
    }

    /**
     * QQ 官方当前仅对可解析为公网 URL 的图片保留直发能力，其余场景依赖显式降级。
     */
    override suspend fun canSendImages(contact: PlatformContact, images: List<ImageSource>): Boolean {
        if (!canSendMessage(contact)) return false
        if (images.isEmpty()) return true
        return resolveImageUrls(images).size == images.size
    }

    /**
     * QQ 官方当前支持通过 msg_id 回复已收到的消息，能力前提仍是联系人可达。
     */
    override suspend fun canReply(contact: PlatformContact): Boolean {
        return canSendMessage(contact)
    }

    // QQ 官方平台没有 OneBot 风格的 @全体能力，这里始终显式返回 false 触发上层降级。
    /**
     * 显式声明 QQ 官方不支持 OneBot 风格的 @全体，要求上层改走降级或提示逻辑。
     */
    override suspend fun canAtAll(contact: PlatformContact): Boolean = false

    private fun hasCredentials(): Boolean {
        return config.appId.isNotBlank() &&
            config.appSecret.isNotBlank()
    }

    /**
     * 建立首轮或重连网关，并在握手完成后切换到新的会话状态。
     */
    private suspend fun connectGateway(initialBootstrap: Boolean) {
        val token = currentAccessToken(forceRefresh = false)
        val gateway = fetchGateway(token)
        val newReadySignal = CompletableDeferred<Unit>()
        readySignal = newReadySignal
        val session = transport.openGateway(
            url = gateway.url,
            headers = apiHeaders(token),
        )

        gatewaySession = session
        gatewayCollectJob?.cancelAndJoin()
        gatewayCollectJob = scope.launch {
            session.incoming.collect { text ->
                handleGatewayFrame(text)
            }
        }
        gatewayCloseWatchJob?.cancelAndJoin()
        gatewayCloseWatchJob = scope.launch {
            val closeCause = session.closeSignal.await()
            if (!started.get()) return@launch
            connected.set(false)
            if (closeCause != null) {
                logger.warn("QQ 官方网关连接断开: ${closeCause.message}")
            } else {
                logger.info("QQ 官方网关连接已关闭")
            }
            requestReconnect()
        }

        try {
            withTimeout(15_000) {
                // 首轮或重连都必须等 READY/RESUMED，避免外层拿到一个尚未完成鉴权的会话。
                newReadySignal.await()
            }
            reconnectAttempts.set(0)
            reconnectGuard.set(false)
        } catch (throwable: Throwable) {
            session.close("bootstrap timeout")
            if (initialBootstrap) {
                throw throwable
            }
            requestReconnect()
        }
    }

    /**
     * 解析网关帧并驱动 hello/ready/业务事件/管理事件的运行时状态更新。
     */
    private suspend fun handleGatewayFrame(text: String) {
        val frame = QQOfficialJson.decodeFromString<QQOfficialGatewayFrame>(text)
        frame.s?.let { lastSeq = it }
        when (frame.op) {
            0 -> handleDispatchFrame(frame)
            7 -> {
                logger.warn("QQ 官方网关要求客户端重连")
                requestReconnect()
            }
            9 -> {
                logger.warn("QQ 官方网关返回 invalid session，清理会话后重连")
                sessionId = null
                lastSeq = null
                requestReconnect()
            }
            10 -> handleHelloFrame(frame.d)
            11 -> Unit
            else -> logger.debug("忽略未处理的 QQ 官方网关 op={}", frame.op)
        }
    }

    /**
     * 收到 Hello 后立刻启动心跳，并根据是否持有 session_id 决定 identify 还是 resume。
     */
    private suspend fun handleHelloFrame(payload: JsonElement?) {
        val hello = payload?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialHelloData>(it) } ?: QQOfficialHelloData()
        startHeartbeat(hello.heartbeatInterval)
        if (sessionId.isNullOrBlank()) {
            sendIdentify()
        } else {
            sendResume()
        }
    }

    /**
     * 分发 Ready/Resumed/消息事件/管理事件，统一更新运行时联系人状态。
     */
    private suspend fun handleDispatchFrame(frame: QQOfficialGatewayFrame) {
        when (frame.t) {
            "READY" -> {
                val ready = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialReadyData>(it) } ?: return
                sessionId = ready.sessionId
                selfOpenId = ready.user.id
                connected.set(true)
                readySignal?.complete(Unit)
                logger.info("QQ 官方网关 READY: self={}", selfOpenId)
            }
            "RESUMED" -> {
                connected.set(true)
                readySignal?.complete(Unit)
                logger.info("QQ 官方网关已恢复会话: session={}", sessionId)
            }
            "GROUP_AT_MESSAGE_CREATE" -> {
                normalizeMessage(frame, PlatformChatType.GROUP)?.let { inbound ->
                    markReachable(inbound.chatContact)
                    recordInboundEvent(inbound)
                }
            }
            "C2C_MESSAGE_CREATE" -> {
                normalizeMessage(frame, PlatformChatType.PRIVATE)?.let { inbound ->
                    markReachable(inbound.chatContact)
                    recordInboundEvent(inbound)
                }
            }
            "GROUP_ADD_ROBOT", "GROUP_MSG_RECEIVE" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialGroupManageEvent>(it) } ?: return
                event.groupOpenId?.let { groupOpenId ->
                    markReachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, groupOpenId))
                }
            }
            "GROUP_DEL_ROBOT", "GROUP_MSG_REJECT" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialGroupManageEvent>(it) } ?: return
                event.groupOpenId?.let { groupOpenId ->
                    markUnreachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, groupOpenId))
                }
            }
            "FRIEND_ADD", "C2C_MSG_RECEIVE" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialC2CManageEvent>(it) } ?: return
                event.openid?.let { openId ->
                    markReachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, openId))
                }
            }
            "FRIEND_DEL", "C2C_MSG_REJECT" -> {
                val event = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialC2CManageEvent>(it) } ?: return
                event.openid?.let { openId ->
                    markUnreachable(PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, openId))
                }
            }
        }
    }

    /**
     * 将 QQ 官方 dispatch 消息归一化为平台无关的入站模型，供命令链与监听链直接消费。
     */
    private fun normalizeMessage(frame: QQOfficialGatewayFrame, chatType: PlatformChatType): PlatformInboundMessage? {
        val message = frame.d?.let { QQOfficialJson.decodeFromJsonElement<QQOfficialMessageEvent>(it) } ?: return null
        val chatContact = when (chatType) {
            PlatformChatType.GROUP -> {
                val groupOpenId = message.groupOpenId ?: return null
                PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, groupOpenId)
            }
            PlatformChatType.PRIVATE -> {
                val userOpenId = message.author.userOpenId ?: return null
                PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, userOpenId)
            }
        }
        val senderContact = when (chatType) {
            PlatformChatType.GROUP -> {
                val memberOpenId = message.author.memberOpenId ?: return null
                PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, memberOpenId)
            }
            PlatformChatType.PRIVATE -> {
                val userOpenId = message.author.userOpenId ?: return null
                PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, userOpenId)
            }
        }
        val searchTexts = buildList {
            message.content.trim().takeIf { it.isNotEmpty() }?.let(::add)
            message.attachments.mapNotNullTo(this) { attachment ->
                attachment.url?.trim()?.takeIf { it.isNotEmpty() }
            }
        }.distinct()

        return PlatformInboundMessage(
            platform = PlatformType.QQ_OFFICIAL,
            chatType = chatType,
            chatContact = chatContact,
            senderContact = senderContact,
            selfContact = PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, selfOpenId),
            messageText = message.content,
            searchTexts = searchTexts,
            hasMention = chatType == PlatformChatType.GROUP,
            fromSelf = senderContact.id == selfOpenId,
            rawPayload = frame,
        )
    }

    /**
     * 启动 QQ 官方网关心跳，维持主动发送所需的在线状态。
     */
    private fun startHeartbeat(intervalMillis: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (started.get()) {
                val payload = buildJsonObject {
                    put("op", 1)
                    put("d", lastSeq?.let(::JsonPrimitive) ?: JsonNull)
                }
                gatewaySession?.sendText(payload.toString())
                delay(intervalMillis.toLong())
            }
        }
    }

    /**
     * 发送 identify，声明当前仅监听公域群聊/C2C 事件。
     */
    private suspend fun sendIdentify() {
        val token = currentAccessToken(forceRefresh = false)
        val payload = buildJsonObject {
            put("op", 2)
            put("d", buildJsonObject {
                put("token", "QQBot $token")
                put("intents", QQ_OFFICIAL_GATEWAY_INTENT_PUBLIC_MESSAGES)
                put("shard", buildJsonArray {
                    add(JsonPrimitive(0))
                    add(JsonPrimitive(1))
                })
                put("properties", buildJsonObject {
                    put("\$os", "windows")
                    put("\$browser", "dynamic-bot")
                    put("\$device", "dynamic-bot")
                })
            })
        }
        gatewaySession?.sendText(payload.toString())
    }

    /**
     * 发送 resume，在网关允许续传时尽量保留上一条 seq 的上下文。
     */
    private suspend fun sendResume() {
        val token = currentAccessToken(forceRefresh = false)
        val payload = buildJsonObject {
            put("op", 6)
            put("d", buildJsonObject {
                put("token", "QQBot $token")
                put("session_id", sessionId.orEmpty())
                put("seq", lastSeq ?: 0)
            })
        }
        gatewaySession?.sendText(payload.toString())
    }

    /**
     * 获取当前 access token，并在接近过期时自动刷新。
     */
    private suspend fun currentAccessToken(forceRefresh: Boolean): String = authMutex.withLock {
        val now = currentTimeMillis()
        val cached = accessToken
        if (!forceRefresh && cached != null && now < accessTokenExpireAtMillis) {
            return cached
        }

        val response = transport.postJson(
            url = QQ_OFFICIAL_TOKEN_URL,
            body = buildJsonObject {
                put("appId", config.appId)
                put("clientSecret", config.appSecret)
            },
            headers = emptyMap(),
        )
        val token = response["access_token"]?.jsonPrimitive?.content.orEmpty()
        val expiresInSeconds = response["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        check(token.isNotBlank()) { "QQ 官方 access token 获取失败: $response" }

        // 预留一小段刷新窗口，避免请求恰好撞上服务端侧的过期切换点。
        accessToken = token
        accessTokenExpireAtMillis = now + (expiresInSeconds * 1000L) - 10_000L
        return token
    }

    /**
     * 读取网关接入点，确保启动时能明确知道握手目标而不是静默降级。
     */
    private suspend fun fetchGateway(token: String): QQOfficialGatewayResponse {
        val response = transport.getJson(
            url = "$QQ_OFFICIAL_API_BASE/gateway/bot",
            headers = apiHeaders(token),
        )
        val gateway = QQOfficialJson.decodeFromJsonElement<QQOfficialGatewayResponse>(response)
        check(gateway.url.isNotBlank()) { "QQ 官方 gateway/bot 返回为空: $response" }
        return gateway
    }

    /**
     * 将发送片段收敛为 QQ 官方最小可用发送计划，避免业务层继续感知平台差异。
     */
    private fun buildSendPlan(parts: List<OutgoingPart>): QQOfficialSendPlan {
        val textBuilder = StringBuilder()
        val images = mutableListOf<ImageSource>()
        var replyId: String? = null
        var unsupportedAtAll = false

        parts.forEach { part ->
            when (part) {
                is OutgoingPart.Text -> textBuilder.append(part.text)
                is OutgoingPart.Image -> images += part.source
                is OutgoingPart.Reply -> replyId = part.messageId.toString()
                is OutgoingPart.MentionAll -> unsupportedAtAll = true
            }
        }

        return QQOfficialSendPlan(
            content = textBuilder.toString(),
            images = images,
            replyId = replyId,
            unsupportedAtAll = unsupportedAtAll,
        )
    }

    /**
     * 解析可直接被 QQ 官方富媒体上传接口消费的公网图片 URL。
     */
    private suspend fun resolveImageUrls(images: List<ImageSource>): List<String> {
        return images.mapNotNull { source ->
            imageUrlResolver(source)?.takeIf { candidate ->
                candidate.startsWith("http://") || candidate.startsWith("https://")
            }
        }
    }

    /**
     * 上传单张图片并返回 media 描述，供随后的 msg_type=7 发送请求复用。
     */
    private suspend fun uploadMedia(contact: PlatformContact, imageUrl: String): QQOfficialMedia {
        val response = transport.postJson(
            url = filesUrl(contact),
            body = buildJsonObject {
                put("file_type", 1)
                put("url", imageUrl)
                put("srv_send_msg", false)
            },
            headers = apiHeaders(currentAccessToken(forceRefresh = false)),
        )
        return QQOfficialJson.decodeFromJsonElement(response)
    }

    /**
     * 向群聊或 C2C 发送一条最终消息，请求体统一收口在这里。
     */
    private suspend fun postMessage(
        contact: PlatformContact,
        msgType: Int,
        content: String,
        media: QQOfficialMedia?,
        replyId: String?,
    ) {
        transport.postJson(
            url = messagesUrl(contact),
            body = buildJsonObject {
                put("msg_type", msgType)
                if (content.isNotBlank()) {
                    put("content", content)
                }
                if (!replyId.isNullOrBlank()) {
                    put("msg_id", replyId)
                    put("msg_seq", 1)
                }
                if (media != null) {
                    put("media", QQOfficialJson.encodeToJsonElement(media))
                }
            },
            headers = apiHeaders(currentAccessToken(forceRefresh = false)),
        )
    }

    /**
     * 统一生成 OpenAPI 请求头，保持 Authorization 与 X-Union-Appid 一致。
     */
    private fun apiHeaders(token: String): Map<String, String> {
        return mapOf(
            "Authorization" to "QQBot $token",
            "X-Union-Appid" to config.appId,
            "User-Agent" to "dynamic-bot",
        )
    }

    /**
     * 记录已接受主动消息的联系人，供能力判断和后续发送入口复用。
     */
    private suspend fun markReachable(contact: PlatformContact) {
        val subject = contact.toSubject()
        val now = currentTimeMillis()
        reachableContactsMutex.withLock {
            // 先清掉已过期条目，再刷新当前联系人，避免 7x24 运行时缓存无界增长。
            pruneReachableContactsLocked(now)
            reachableContacts.remove(subject)
            reachableContacts[subject] = now
            enforceReachableContactLimitLocked()
        }
    }

    /**
     * 在群退群/拒收等事件到来时移除联系人，避免继续误发主动消息。
     */
    private suspend fun markUnreachable(contact: PlatformContact) {
        reachableContactsMutex.withLock {
            reachableContacts.remove(contact.toSubject())
        }
    }

    /**
     * 停机时显式清空联系人缓存，避免当前代 adapter 退场后仍短暂持有大批历史联系人状态。
     */
    private suspend fun clearReachableContacts() {
        reachableContactsMutex.withLock {
            reachableContacts.clear()
        }
    }

    /**
     * 在同一把锁内统一执行过期回收与容量裁剪，保证联系人缓存始终受 TTL 和上限双重约束。
     */
    private fun pruneReachableContactsLocked(now: Long) {
        val expireBefore = now - effectiveReachableContactTtlMillis
        val iterator = reachableContacts.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value <= expireBefore) {
                iterator.remove()
            }
        }
        enforceReachableContactLimitLocked()
    }

    /**
     * 缓存超上限时按最旧活跃时间淘汰，确保主动发送能力只保留最近接触的联系人。
     */
    private fun enforceReachableContactLimitLocked() {
        while (reachableContacts.size > effectiveReachableContactsMaxSize) {
            val oldestSubject = reachableContacts.entries.firstOrNull()?.key ?: return
            reachableContacts.remove(oldestSubject)
        }
    }

    /**
     * 显式记录入站 SharedFlow 溢出，避免 QQ 官方链路在高负载下静默丢事件。
     */
    private fun recordInboundEvent(inbound: PlatformInboundMessage) {
        if (_eventFlow.tryEmit(inbound)) {
            inboundPressureActive.set(false)
            return
        }
        inboundPressureActive.set(true)
        val droppedCount = inboundDroppedEvents.incrementAndGet()
        logger.warn("QQ 官方入站事件背压触发，已累计丢弃 $droppedCount 条事件")
    }

    /**
     * 统一调度重连，避免同一时刻并发建立多条网关连接。
     */
    private fun requestReconnect() {
        if (!started.get()) return
        reconnectGuard.set(true)
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            runReconnectLoop()
        }
    }

    /**
     * 使用单一重连循环串行处理 QQ 官方重连，避免递归调度造成重试风暴。
     */
    private suspend fun runReconnectLoop() {
        while (started.get() && reconnectGuard.get()) {
            val attempt = reconnectAttempts.incrementAndGet()
            // 先停掉旧代际的心跳和收帧协程，避免新旧连接并发写同一份 session 状态。
            heartbeatJob?.cancelAndJoin()
            gatewayCollectJob?.cancelAndJoin()
            gatewaySession?.close("reconnect")
            val backoffDelay = reconnectBackoffPolicy.nextDelayMillis(attempt)
            delay(backoffDelay)
            val connectedNow = runCatching {
                connectGateway(initialBootstrap = false)
                true
            }.getOrElse {
                logger.error("QQ 官方网关重连失败: ${it.message}", it)
                false
            }
            if (connectedNow) {
                reconnectGuard.set(false)
                return
            }
        }
    }

    /**
     * 根据联系人类型拼接消息发送地址，避免业务层继续拼接群/私聊路径。
     */
    private fun messagesUrl(contact: PlatformContact): String {
        return when (contact.type) {
            PlatformChatType.GROUP -> "$QQ_OFFICIAL_API_BASE/v2/groups/${contact.id}/messages"
            PlatformChatType.PRIVATE -> "$QQ_OFFICIAL_API_BASE/v2/users/${contact.id}/messages"
        }
    }

    /**
     * 根据联系人类型拼接富媒体上传地址，与发送地址保持同一命名约定。
     */
    private fun filesUrl(contact: PlatformContact): String {
        return when (contact.type) {
            PlatformChatType.GROUP -> "$QQ_OFFICIAL_API_BASE/v2/groups/${contact.id}/files"
            PlatformChatType.PRIVATE -> "$QQ_OFFICIAL_API_BASE/v2/users/${contact.id}/files"
        }
    }

    companion object {
        internal const val DEFAULT_REACHABLE_CONTACT_TTL_MILLIS: Long = 24L * 60L * 60L * 1000L
        internal const val DEFAULT_REACHABLE_CONTACTS_MAX_SIZE: Int = 10_000
    }
}

private data class QQOfficialSendPlan(
    val content: String,
    val images: List<ImageSource>,
    val replyId: String?,
    val unsupportedAtAll: Boolean,
)

/**
 * QQ 官方富媒体上传接口当前直接接受公网 URL；本地/二进制图片需由上层另行提供外链能力。
 */
private suspend fun defaultImageUrlResolver(source: ImageSource): String? {
    return when (source) {
        is ImageSource.RemoteUrl -> source.url
        is ImageSource.LocalFile -> null
        is ImageSource.Binary -> null
    }
}
