package top.bilibili.connector.onebot11.vendors.llbot

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.ConnectionPool
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal val llBotJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

internal interface LlBotTransport : Closeable {
    /**
     * 打开 llbot WebSocket 会话，并把文本帧暴露为可测试的抽象会话。
     */
    suspend fun openGateway(url: String, headers: Map<String, String>): LlBotGatewaySession
}

internal interface LlBotGatewaySession {
    val incoming: Flow<String>
    val closeSignal: CompletableDeferred<Throwable?>

    /**
     * 发送单个 OneBot11 JSON 文本帧。
     */
    suspend fun sendText(text: String)

    /**
     * 主动关闭当前 llbot 会话，供停机与重连流程复用。
     */
    suspend fun close(reason: String = "shutdown")
}

/**
 * llbot 的默认 Ktor WebSocket 传输实现，仅位于 vendor 层内供 client 组合使用。
 */
internal class KtorLlBotTransport : LlBotTransport {
    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 30_000
        }
        install(WebSockets)
        engine {
            config {
                connectionPool(
                    ConnectionPool(
                        3,
                        3,
                        TimeUnit.MINUTES,
                    ),
                )
            }
        }
    }

    /**
     * 建立真实 WebSocket 会话，并将底层收发包装为 client 可测试的 gateway session。
     */
    override suspend fun openGateway(url: String, headers: Map<String, String>): LlBotGatewaySession {
        val incomingFlow = MutableSharedFlow<String>(replay = 32, extraBufferCapacity = 64)
        val closeSignal = CompletableDeferred<Throwable?>()
        val outgoingChannel = Channel<String>(64)
        val sessionRef = AtomicReference<DefaultClientWebSocketSession?>()
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch {
            var failure: Throwable? = null
            try {
                client.webSocket(
                    urlString = url,
                    request = {
                        headers.forEach { (name, value) ->
                            header(name, value)
                        }
                    },
                ) {
                    sessionRef.set(this)
                    val sendJob = launch {
                        for (text in outgoingChannel) {
                            send(Frame.Text(text))
                        }
                    }
                    try {
                        for (frame in incoming) {
                            when (frame) {
                                is Frame.Text -> incomingFlow.emit(frame.readText())
                                is Frame.Close -> break
                                else -> Unit
                            }
                        }
                    } finally {
                        sendJob.cancelAndJoin()
                    }
                }
            } catch (throwable: Throwable) {
                failure = throwable
            } finally {
                outgoingChannel.close()
                if (!closeSignal.isCompleted) {
                    closeSignal.complete(failure)
                }
            }
        }

        return object : LlBotGatewaySession {
            override val incoming: Flow<String> = incomingFlow
            override val closeSignal: CompletableDeferred<Throwable?> = closeSignal

            /**
             * 将 OneBot11 JSON 文本帧推送到当前网关连接。
             */
            override suspend fun sendText(text: String) {
                outgoingChannel.send(text)
            }

            /**
             * 主动关闭当前 llbot 会话，确保停机时不残留悬挂连接。
             */
            override suspend fun close(reason: String) {
                outgoingChannel.close()
                sessionRef.get()?.close(CloseReason(CloseReason.Codes.NORMAL, reason))
                job.cancelAndJoin()
                if (!closeSignal.isCompleted) {
                    closeSignal.complete(null)
                }
            }
        }
    }

    /**
     * 关闭底层 Ktor 客户端，释放 WebSocket 与 HTTP 资源。
     */
    override fun close() {
        client.close()
    }
}
