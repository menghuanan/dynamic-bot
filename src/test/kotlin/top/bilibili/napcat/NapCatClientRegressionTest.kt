package top.bilibili.connector.onebot11.vendors.napcat

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import top.bilibili.config.NapCatConfig
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NapCatClientRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

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
