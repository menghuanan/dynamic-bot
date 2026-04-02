package top.bilibili.connector.onebot11.vendors.napcat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import top.bilibili.config.NapCatConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NapCatClientRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    /**
     * 通过反射注入停机阶段依赖，确保回归测试可以稳定复现 NapCatClient 的真实 shutdown 调度行为。
     */
    private fun setPrivateField(instance: Any, fieldName: String, value: Any?) {
        val field = instance::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(instance, value)
    }

    @Test
    fun `isSendQueueFull should not enqueue probe payload`() {
        val client = NapCatClient(NapCatConfig())

        repeat(5) {
            client.isSendQueueFull()
        }

        val field = NapCatClient::class.java.getDeclaredField("sendChannel")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val channel = field.get(client) as Channel<String>

        var drained = 0
        while (true) {
            val result = channel.tryReceive()
            if (result.isSuccess) {
                drained++
            } else {
                break
            }
        }

        assertEquals(0, drained, "queue probe must not enqueue synthetic messages")
    }

    @Test
    fun `heartbeat meta event should decode successfully`() {
        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        val payload = """
            {"time":1772723454,"self_id":1570634086,"post_type":"meta_event","meta_event_type":"heartbeat","status":{"online":true,"good":true},"interval":30000}
        """.trimIndent()

        val event = json.decodeFromString<MetaEvent>(payload)
        assertEquals("meta_event", event.postType)
        assertEquals("heartbeat", event.metaEventType)
        assertTrue(event.status?.isNotEmpty() == true)
    }

    @Test
    fun `outgoing log should mask long base64 payload`() {
        val client = NapCatClient(NapCatConfig())
        val raw = """
            {"action":"send_group_msg","params":{"group_id":1,"message":[{"type":"image","data":{"file":"base64://ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=ABCDEFGHIJKLMNOPQRSTUVWXYZ"}}]}}
        """.trimIndent()

        val simplified = client.simplifyOutgoingMessageForLog(raw)
        assertTrue(simplified.contains("base64://"), "prefix should remain for diagnosis")
        assertTrue(simplified.contains("..."), "masked output should contain ellipsis")
        assertFalse(
            simplified.contains("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/=ABCDEFGHIJKLMNOPQRSTUVWXYZ"),
            "full base64 payload should not appear in logs"
        )
    }

    @Test
    fun `base64 send mode should preserve already encoded image payloads`() {
        val source = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")

        // 适配器已把本地图编码成 base64 时，NapCat 不应再尝试回退到本地文件读取。
        assertTrue(
            source.contains("imageSource.startsWith(\"base64://\")"),
            "NapCatClient should detect adapter-encoded base64 image payloads",
        )
        // 已编码图片段必须走跳过分支，避免重复转换破坏当前发送链路。
        assertTrue(
            source.contains("跳过重复转换"),
            "NapCatClient should skip duplicate base64 conversion for already encoded images",
        )
    }

    @Test
    fun `incoming log simplification should use shared placeholder mapping`() {
        val client = NapCatClient(NapCatConfig())

        val simplified = client.simplifyIncomingMessageForLog(
            "[CQ:share,url=https://example.com][CQ:poke,qq=1][CQ:music,type=qq,id=1]"
        )

        assertEquals("[\u5206\u4eab][\u6233\u4e00\u6233][\u97f3\u4e50]", simplified)
    }
    @Test
    fun `outgoing log preview should summarize message segments`() {
        val client = NapCatClient(NapCatConfig())

        val preview = client.buildOutgoingLogPreview(
            listOf(
                MessageSegment.text("hello"),
                MessageSegment.image("file:///cover.png"),
                MessageSegment("poke", mapOf("qq" to "1")),
                MessageSegment("markdown", mapOf("data" to "# title"))
            )
        )

        assertEquals("hello[\u56fe\u7247][\u6233\u4e00\u6233][Markdown]", preview)
    }

    @Test
    fun `stop should release caller dispatcher while waiting for liveness shutdown`() = runBlocking {
        val client = NapCatClient(NapCatConfig())
        val callerDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val shutdownScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val callerScope = CoroutineScope(callerDispatcher + SupervisorJob())

        try {
            // 构造一个带非取消清理阶段的 watcher，模拟 stop() 等待慢清理任务时的真实场景。
            val slowLivenessWatcher = shutdownScope.launch {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(300)
                    }
                }
            }
            setPrivateField(client, "livenessWatchJob", slowLivenessWatcher)

            val peerCoroutineObserved = AtomicBoolean(false)
            val stopFinished = CompletableDeferred<Unit>()

            val stopJob = callerScope.launch {
                launch {
                    delay(50)
                    peerCoroutineObserved.set(true)
                }
                client.stop()
                stopFinished.complete(Unit)
            }

            withTimeout(150) {
                while (!peerCoroutineObserved.get()) {
                    delay(5)
                }
            }
            withTimeout(1_000) {
                stopFinished.await()
            }
            stopJob.join()
        } finally {
            callerScope.cancel()
            shutdownScope.cancel()
            callerDispatcher.close()
        }
    }

    @Test
    fun `stop should close websocket before cancelling scope and suppress shutdown cancellation noise`() {
        val source = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")

        assertTrue(
            source.contains("private val stopping = AtomicBoolean(false)"),
            "NapCatClient should track explicit stopping state",
        )
        assertTrue(
            source.contains("stopping.set(true)"),
            "NapCatClient.stop should mark the client as stopping before shutdown actions",
        )
        assertTrue(
            source.indexOf("session?.close") in 0 until source.indexOf("cancelAndJoin"),
            "WebSocket session should close before scope cancellation to reduce shutdown noise",
        )
        assertTrue(
            source.contains("if (stopping.get())"),
            "receive/connect loops should suppress expected cancellation noise during shutdown",
        )
    }

    @Test
    fun `send message timeout should be set to 20 seconds`() {
        val source = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")

        assertTrue(
            source.contains("timeoutMillis = 20_000L"),
            "NapCatClient should wait 20 seconds for send message responses",
        )
        assertTrue(
            !source.contains("SEND_MESSAGE_TIMEOUT_MS"),
            "NapCatClient should keep the send timeout inlined when using the direct style",
        )
    }

    @Test
    fun `start should ignore duplicate request when connection loop is already active`() = runBlocking {
        val client = NapCatClient(NapCatConfig())
        val activeConnectionJob = launch {
            awaitCancellation()
        }

        try {
            setPrivateField(client, "connectionJob", activeConnectionJob)

            client.start()

            val field = NapCatClient::class.java.getDeclaredField("connectionJob")
            field.isAccessible = true
            val currentJob = field.get(client) as Job?
            assertTrue(currentJob === activeConnectionJob, "duplicate start should keep the original connection job")
        } finally {
            activeConnectionJob.cancel()
        }
    }

    @Test
    fun `napcat reconnect should use shared bounded backoff and application liveness watchdog`() {
        val source = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")
        val policySource = read("src/main/kotlin/top/bilibili/connector/ConnectionBackoffPolicy.kt")

        assertTrue(policySource.contains("class ConnectionBackoffPolicy"))
        assertTrue(policySource.contains("nextDelayMillis"))
        assertTrue(source.contains("ConnectionBackoffPolicy"))
        assertFalse(source.contains("delay(config.reconnectInterval)"))
        assertTrue(source.contains("liveness"))
        assertTrue(source.contains("lastInboundAtMillis"))
    }

    // NapCat 传输层常驻 WebSocket 只需要极小连接池，避免额外空闲连接长期占用 native 资源。
    @Test
    fun `napcat client should keep a minimal okhttp idle pool`() {
        val source = read("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")

        assertTrue(
            source.contains("maxIdleConnections = 1"),
            "NapCatClient should keep only one idle connection in pool",
        )
        assertTrue(
            source.contains("keepAliveDuration = 1"),
            "NapCatClient should use 1 minute keep-alive to reduce idle residency",
        )
    }

    // 约束 NapCat 特有的群能力映射必须留在 vendor 适配层，避免再次泄漏回通用 OneBot11 核心。
    @Test
    fun `napcat vendor adapter should keep napcat specific capability mapping`() {
        val vendorPath = Path.of("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatAdapter.kt")
        val coreSource = read("src/main/kotlin/top/bilibili/connector/onebot11/OneBot11Adapter.kt")

        assertTrue(Files.exists(vendorPath), "NapCat vendor adapter should exist")
        val vendorSource = Files.readString(vendorPath, StandardCharsets.UTF_8)
        assertTrue(vendorSource.contains("NapCatClient"))
        assertTrue(vendorSource.contains("isBotInGroup"))
        assertTrue(vendorSource.contains("canAtAllInGroup"))
        assertFalse(coreSource.contains("isBotInGroup"))
        assertFalse(coreSource.contains("canAtAllInGroup"))
    }
}
