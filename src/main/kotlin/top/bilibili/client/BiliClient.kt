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
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

data class ApiRequestTrace(
    val source: String,
    val api: String,
    val url: String,
)

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

private fun ApiRequestTrace.logApiLabel(): String {
    return when (api) {
        "NEW_DYNAMIC" -> "动态列表"
        "LIVE_LIST" -> "关注直播列表"
        "LIVE_STATUS_BATCH" -> "直播状态批量查询"
        "未知接口", "unknown" -> "未知接口"
        else -> api
    }
}

private fun Throwable.logTypeLabel(): String {
    return when (this) {
        is HttpRequestTimeoutException, is SocketTimeoutException -> "请求超时"
        is UnknownHostException -> "域名解析失败"
        is ConnectException -> "连接失败"
        is IOException -> "网络异常"
        else -> "未分类异常"
    }
}

private fun requestTimeoutSeconds(): Int {
    return runCatching { BiliConfigManager.config.checkConfig.timeout }.getOrDefault(10)
}

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

open class BiliClient : Closeable {
    override fun close() = clients.forEach { it.close() }

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

    val clients = MutableList(3) { client() }

    protected fun client() = HttpClient(OkHttp) {
        engine {
            config {
                connectionPool(
                    okhttp3.ConnectionPool(
                        maxIdleConnections = 5,
                        keepAliveDuration = 5,
                        timeUnit = TimeUnit.MINUTES,
                    )
                )
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

    suspend fun <T> useHttpClient(
        trace: ApiRequestTrace = ApiRequestTrace(source = "未知任务", api = "未知接口", url = "unknown"),
        block: suspend (HttpClient) -> T,
    ): T = supervisorScope {
        var retryCount = 0
        val maxRetries = 1

        while (isActive) {
            try {
                val selectedClientIndex = clientIndex
                val proxies = proxys
                val client = clients[selectedClientIndex]
                if (!proxies.isNullOrEmpty() && BiliConfigManager.config.enableConfig.proxyEnable) {
                    client.engineConfig.proxy = proxies[proxyIndex]
                    proxyIndex = (proxyIndex + 1) % proxies.size
                }
                return@supervisorScope block(client)
            } catch (throwable: Throwable) {
                if (isActive && (throwable is IOException || throwable is HttpRequestTimeoutException)) {
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
                    clientIndex = (clientIndex + 1) % clients.size
                } else {
                    logger.error("API请求发生不可重试异常: 异常=${throwable.logTypeLabel()}, 原因=${throwable.logReason()}", throwable)
                    throw throwable
                }
            }
        }
        throw CancellationException()
    }
}
