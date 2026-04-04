package top.bilibili.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.engine.ProxyConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.UserAgent
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.utils.io.core.Closeable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.json.Json
import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.decode
import top.bilibili.utils.isNotBlank
import top.bilibili.utils.json
import java.io.IOException
import java.lang.ref.WeakReference
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.ConnectionPool
import okhttp3.Dispatcher

/**
 * API 请求追踪信息，用于日志中标记调用来源与接口名称。
 *
 * @param source 请求来源
 * @param api 接口标识
 * @param url 请求地址
 */
data class ApiRequestTrace(
    val source: String,
    val api: String,
    val url: String,
)

/**
 * 将请求来源转换为更易读的日志标签。
 */
private fun ApiRequestTrace.logSourceLabel(): String {
    return when (source) {
        "DynamicCheckTasker.poll" -> "动态轮询"
        "DynamicCheckTasker.manual-check" -> "动态手动检查"
        "LiveCheckTasker.followed-live-list" -> "直播轮询(关注列表)"
        "LiveCheckTasker.subscribed-live-status" -> "直播轮询(订阅状态)"
        "未知任务", "unknown" -> "未知任务"
        else -> source
    }
}

/**
 * 将接口标识转换为更易读的日志标签。
 */
private fun ApiRequestTrace.logApiLabel(): String {
    return when (api) {
        "NEW_DYNAMIC" -> "动态列表"
        "LIVE_LIST" -> "关注直播列表"
        "LIVE_STATUS_BATCH" -> "直播状态批量查询"
        "未知接口", "unknown" -> "未知接口"
        else -> api
    }
}

/**
 * 将异常类型归类为统一日志标签。
 */
private fun Throwable.logTypeLabel(): String {
    return when (this) {
        is HttpRequestTimeoutException, is SocketTimeoutException -> "请求超时"
        is UnknownHostException -> "域名解析失败"
        is ConnectException -> "连接失败"
        is IOException -> "网络异常"
        else -> "未分类异常"
    }
}

/**
 * 读取当前请求超时配置，失败时回退到默认值。
 */
private fun requestTimeoutSeconds(): Int {
    return runCatching { BiliConfigManager.config.checkConfig.timeout }.getOrDefault(10)
}

/**
 * 生成适合日志展示的失败原因描述。
 */
private fun Throwable.logReason(): String {
    return when (this) {
        is HttpRequestTimeoutException, is SocketTimeoutException -> "${requestTimeoutSeconds()}秒内未收到响应"
        is UnknownHostException -> "无法解析目标域名"
        is ConnectException -> "无法建立连接"
        is IOException -> "网络读写失败"
        else -> message?.trim().takeUnless { it.isNullOrEmpty() } ?: "未提供详细信息"
    }
}

@Suppress("UNUSED_PARAMETER")
/**
 * 构建 API 重试日志文案。
 */
fun buildRetryLogMessage(
    trace: ApiRequestTrace,
    retryNumber: Int,
    maxAttempts: Int,
    clientIndex: Int,
    proxyEnabled: Boolean,
    throwable: Throwable,
): String {
    return buildString {
        append("API请求失败，3秒后进行第")
        append(retryNumber)
        append("次重试: ")
        append("任务=")
        append(trace.logSourceLabel())
        append(", 接口=")
        append(trace.logApiLabel())
        append(", 异常=")
        append(throwable.logTypeLabel())
        append(", 原因=")
        append(throwable.logReason())
    }
}

@Suppress("UNUSED_PARAMETER")
/**
 * 构建 API 重试耗尽日志文案。
 */
fun buildRetryExhaustedLogMessage(
    trace: ApiRequestTrace,
    attemptsUsed: Int,
    maxAttempts: Int,
    clientIndex: Int,
    proxyEnabled: Boolean,
    throwable: Throwable,
): String {
    return buildString {
        append("API请求重试耗尽: ")
        append("任务=")
        append(trace.logSourceLabel())
        append(", 接口=")
        append(trace.logApiLabel())
        append(", 重试=")
        append(attemptsUsed)
        append("/")
        append(maxAttempts)
        append(", 异常=")
        append(throwable.logTypeLabel())
        append(", 原因=")
        append(throwable.logReason())
    }
}

/**
 * 单个 retry 槽位的运行期快照。
 *
 * @param slotIndex 槽位索引
 * @param created 该槽位是否已经创建底层客户端
 * @param connectionCount 当前连接池中的连接总数
 * @param idleConnectionCount 当前连接池中的空闲连接数
 * @param queuedCallsCount 当前调度器中排队中的请求数
 * @param runningCallsCount 当前调度器中运行中的请求数
 */
data class BiliClientRetrySlotSnapshot(
    val slotIndex: Int,
    val created: Boolean,
    val connectionCount: Int?,
    val idleConnectionCount: Int?,
    val queuedCallsCount: Int?,
    val runningCallsCount: Int?,
)

/**
 * 单个 BiliClient 实例的运行期快照。
 *
 * @param instanceId 进程内实例编号
 * @param ownerTag 实例所属标签
 * @param createdRetrySlotCount 已创建的 retry 槽位数量
 * @param retrySlotCapacity retry 槽位容量
 * @param retrySlots 各槽位运行态
 */
data class BiliClientInstanceSnapshot(
    val instanceId: Int,
    val ownerTag: String,
    val createdRetrySlotCount: Int,
    val retrySlotCapacity: Int,
    val retrySlots: List<BiliClientRetrySlotSnapshot>,
)

/**
 * 全局 BiliClient 运行期快照。
 *
 * @param totalCreatedCount 进程生命周期内累计创建的实例数
 * @param activeInstanceCount 当前仍活跃的实例数
 * @param createdRetrySlotCount 当前活跃实例中已创建的 retry 槽位数
 * @param retrySlotCapacity 当前活跃实例理论总槽位容量
 * @param instances 活跃实例快照列表
 */
data class BiliClientRuntimeSnapshot(
    val totalCreatedCount: Int,
    val activeInstanceCount: Int,
    val createdRetrySlotCount: Int,
    val retrySlotCapacity: Int,
    val instances: List<BiliClientInstanceSnapshot>,
)

/**
 * B 站 API 客户端，封装请求头、代理、重试与超时配置。
 *
 * @param ownerTag 运行期实例标签，用于守护日志区分共享客户端来源
 */
open class BiliClient(
    private val ownerTag: String = "anonymous",
) : Closeable {
    companion object {
        private const val RETRY_SLOT_CAPACITY = 2
        private const val RETRY_POOL_MAX_IDLE_CONNECTIONS = 2
        private const val RETRY_POOL_KEEP_ALIVE_MINUTES = 1L
        private const val RETRY_DISPATCHER_MAX_REQUESTS = 16
        private const val RETRY_DISPATCHER_MAX_REQUESTS_PER_HOST = 4
        private val instanceSequence = AtomicInteger(0)
        private val totalCreatedCount = AtomicInteger(0)
        private val activeClientRefs = ConcurrentHashMap<Int, WeakReference<BiliClient>>()

        /**
         * 聚合当前进程内所有仍活跃的 BiliClient 快照，供守护日志直接输出。
         */
        @JvmStatic
        fun runtimeSnapshot(): BiliClientRuntimeSnapshot {
            val staleIds = mutableListOf<Int>()
            val instanceSnapshots = activeClientRefs.entries.mapNotNull { (instanceId, reference) ->
                val client = reference.get()
                if (client == null || client.closed) {
                    staleIds.add(instanceId)
                    null
                } else {
                    client.snapshot()
                }
            }.sortedBy { it.instanceId }

            staleIds.forEach { staleId -> activeClientRefs.remove(staleId) }

            return BiliClientRuntimeSnapshot(
                totalCreatedCount = totalCreatedCount.get(),
                activeInstanceCount = instanceSnapshots.size,
                createdRetrySlotCount = instanceSnapshots.sumOf { it.createdRetrySlotCount },
                retrySlotCapacity = instanceSnapshots.sumOf { it.retrySlotCapacity },
                instances = instanceSnapshots,
            )
        }
    }

    private val instanceId = instanceSequence.incrementAndGet()

    @Volatile
    private var closed = false

    init {
        totalCreatedCount.incrementAndGet()
        activeClientRefs[instanceId] = WeakReference(this)
    }

    override fun close() {
        // 先将实例标记为关闭态，再摘出并回收底层客户端，阻断 close 后“复活”创建新槽位。
        val snapshot = synchronized(retrySlots) {
            if (closed) {
                return
            }
            closed = true
            retrySlots.toList().also {
                retrySlots.indices.forEach { index -> retrySlots[index] = null }
            }
        }
        activeClientRefs.remove(instanceId)
        snapshot.filterNotNull().forEach { it.client.close() }
    }

    private val proxys = if (BiliConfigManager.config.proxyConfig.proxy.isNotBlank()) {
        mutableListOf<ProxyConfig>().apply {
            BiliConfigManager.config.proxyConfig.proxy.forEach {
                if (it != "") {
                    add(ProxyBuilder.http(Url(it)))
                }
            }
        }
    } else {
        null
    }

    // 重试槽位默认保留 2 个，但仅在真正轮到某个槽位发请求时才创建，避免轮询刚启动就一次性常驻多套 OkHttp 资源。
    private val retrySlots = MutableList<RetrySlotEntry?>(RETRY_SLOT_CAPACITY) { null }

    /**
     * 创建单个底层 HTTP 客户端实例。
     */
    protected fun client(
        connectionPool: ConnectionPool,
        dispatcher: Dispatcher,
    ) = HttpClient(OkHttp) {
        engine {
            config {
                dispatcher(dispatcher)
                connectionPool(connectionPool)
            }
        }
        defaultRequest {
            header(HttpHeaders.Origin, "https://t.bilibili.com")
            header(HttpHeaders.Referrer, "https://t.bilibili.com")
        }
        install(HttpTimeout) {
            socketTimeoutMillis = BiliConfigManager.config.checkConfig.timeout * 1000L
            connectTimeoutMillis = BiliConfigManager.config.checkConfig.timeout * 1000L
            requestTimeoutMillis = BiliConfigManager.config.checkConfig.timeout * 1000L
        }
        expectSuccess = true
        Json {
            json
        }
        install(UserAgent) {
            agent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/113.0.0.0 Safari/537.36"
        }
    }

    /**
     * 延迟创建指定重试槽位的底层客户端，让健康路径只保留实际用到的连接池与线程资源。
     *
     * @param slotIndex 重试槽位索引
     */
    private fun getOrCreateClient(slotIndex: Int): HttpClient {
        return synchronized(retrySlots) {
            ensureClientOpen()
            retrySlots[slotIndex]?.client ?: createRetrySlot(slotIndex).client
        }
    }

    /**
     * 首次命中某个 retry 槽位时，显式创建对应的连接池与调度器，供守护日志读取运行态。
     *
     * @param slotIndex 重试槽位索引
     */
    private fun createRetrySlot(slotIndex: Int): RetrySlotEntry {
        ensureClientOpen()
        val connectionPool = ConnectionPool(
            maxIdleConnections = RETRY_POOL_MAX_IDLE_CONNECTIONS,
            keepAliveDuration = RETRY_POOL_KEEP_ALIVE_MINUTES,
            timeUnit = TimeUnit.MINUTES,
        )
        val dispatcher = Dispatcher().apply {
            // 轮询链路并发度有限，收紧 dispatcher 可以减少高峰时短期对象滞留。
            maxRequests = RETRY_DISPATCHER_MAX_REQUESTS
            maxRequestsPerHost = RETRY_DISPATCHER_MAX_REQUESTS_PER_HOST
        }
        return RetrySlotEntry(
            slotIndex = slotIndex,
            client = client(connectionPool = connectionPool, dispatcher = dispatcher),
            connectionPool = connectionPool,
            dispatcher = dispatcher,
        ).also { created ->
            retrySlots[slotIndex] = created
        }
    }

    /**
     * 导出当前实例的可验证运行态，避免守护日志再去猜测 HTTP 栈内部资源占用。
     */
    private fun snapshot(): BiliClientInstanceSnapshot {
        val slotSnapshots = synchronized(retrySlots) {
            retrySlots.mapIndexed { slotIndex, entry ->
                if (entry == null) {
                    BiliClientRetrySlotSnapshot(
                        slotIndex = slotIndex,
                        created = false,
                        connectionCount = null,
                        idleConnectionCount = null,
                        queuedCallsCount = null,
                        runningCallsCount = null,
                    )
                } else {
                    BiliClientRetrySlotSnapshot(
                        slotIndex = slotIndex,
                        created = true,
                        connectionCount = entry.connectionPool.connectionCount(),
                        idleConnectionCount = entry.connectionPool.idleConnectionCount(),
                        queuedCallsCount = entry.dispatcher.queuedCallsCount(),
                        runningCallsCount = entry.dispatcher.runningCallsCount(),
                    )
                }
            }
        }

        return BiliClientInstanceSnapshot(
            instanceId = instanceId,
            ownerTag = ownerTag,
            createdRetrySlotCount = slotSnapshots.count { it.created },
            retrySlotCapacity = RETRY_SLOT_CAPACITY,
            retrySlots = slotSnapshots,
        )
    }

    /**
     * 发送 GET 请求并将响应解码为指定类型。
     */
    suspend inline fun <reified T> get(
        url: String,
        trace: ApiRequestTrace = ApiRequestTrace(source = "未知任务", api = url, url = url),
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T =
        useHttpClient<String>(trace) {
            it.get(url) {
                header(HttpHeaders.Cookie, BiliBiliBot.cookie.toHeaderString() + "DedeUserID=" + BiliBiliBot.uid)
                block()
            }.body()
        }.decode()

    /**
     * 发送 POST 请求并将响应解码为指定类型。
     */
    suspend inline fun <reified T> post(
        url: String,
        trace: ApiRequestTrace = ApiRequestTrace(source = "未知任务", api = url, url = url),
        crossinline block: HttpRequestBuilder.() -> Unit = {},
    ): T =
        useHttpClient<String>(trace) {
            it.post(url) {
                header(HttpHeaders.Cookie, BiliBiliBot.cookie.toHeaderString() + "DedeUserID=" + BiliBiliBot.uid)
                block()
            }.body()
        }.decode()

    private var clientIndex = 0
    private var proxyIndex = 0

    private val logger = org.slf4j.LoggerFactory.getLogger(BiliClient::class.java)

    /**
     * 统一判定当前异常是否属于可重试网络错误。
     */
    private fun shouldRetry(throwable: Throwable): Boolean {
        return throwable is IOException || throwable is HttpRequestTimeoutException
    }

    /**
     * 超时类错误通常是瞬时拥塞，不轮换底层槽位可避免抖动时额外拉起常驻 OkHttp 资源。
     */
    private fun shouldRotateRetrySlot(throwable: Throwable): Boolean {
        return throwable !is HttpRequestTimeoutException && throwable !is SocketTimeoutException
    }

    /**
     * 使用底层 HTTP 客户端执行请求，并在可重试错误上做有限次重试。
     */
    suspend fun <T> useHttpClient(
        trace: ApiRequestTrace = ApiRequestTrace(source = "未知任务", api = "未知接口", url = "unknown"),
        block: suspend (HttpClient) -> T,
    ): T = supervisorScope {
        ensureClientOpen()
        var retryCount = 0
        val maxRetries = 1

        while (isActive) {
            try {
                ensureClientOpen()
                val selectedClientIndex = clientIndex
                val proxies = proxys
                val client = getOrCreateClient(selectedClientIndex)
                if (!proxies.isNullOrEmpty() && BiliConfigManager.config.enableConfig.proxyEnable) {
                    // 每次请求轮换代理可分散单节点抖动，避免连续重试都命中同一失效出口。
                    client.engineConfig.proxy = proxies[proxyIndex]
                    proxyIndex = (proxyIndex + 1) % proxies.size
                }
                return@supervisorScope block(client)
            } catch (throwable: Throwable) {
                if (isActive && shouldRetry(throwable)) {
                    val selectedClientIndex = clientIndex
                    val proxies = proxys
                    val proxyEnabled = !proxies.isNullOrEmpty() && BiliConfigManager.config.enableConfig.proxyEnable
                    if (retryCount >= maxRetries) {
                        logger.error(
                            buildRetryExhaustedLogMessage(
                                trace = trace,
                                attemptsUsed = retryCount + 1,
                                maxAttempts = maxRetries + 1,
                                clientIndex = selectedClientIndex,
                                proxyEnabled = proxyEnabled,
                                throwable = throwable,
                            )
                        )
                        throw throwable
                    }
                    logger.warn(
                        buildRetryLogMessage(
                            trace = trace,
                            retryNumber = retryCount + 1,
                            maxAttempts = maxRetries + 1,
                            clientIndex = selectedClientIndex,
                            proxyEnabled = proxyEnabled,
                            throwable = throwable,
                        )
                    )
                    retryCount++
                    delay(3000)
                    if (shouldRotateRetrySlot(throwable)) {
                        // 连接失败类错误才轮换到底层客户端，避免超时抖动时额外拉起新槽位。
                        clientIndex = (clientIndex + 1) % retrySlots.size
                    }
                } else {
                    logger.error("API请求发生不可重试异常: 异常=${throwable.logTypeLabel()}, 原因=${throwable.logReason()}", throwable)
                    throw throwable
                }
            }
        }
        throw CancellationException()
    }

    /**
     * 单个 retry 槽位的底层资源句柄，守护日志读取连接池和调度器状态时直接使用这里的引用。
     */
    private data class RetrySlotEntry(
        val slotIndex: Int,
        val client: HttpClient,
        val connectionPool: ConnectionPool,
        val dispatcher: Dispatcher,
    )

    /**
     * 统一阻断 close 后的请求与槽位创建，避免关闭后的客户端在异常重试路径被“复活”。
     */
    private fun ensureClientOpen() {
        check(!closed) { "BiliClient has been closed and cannot be reused." }
    }
}
