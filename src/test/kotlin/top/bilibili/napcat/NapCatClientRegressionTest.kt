package top.bilibili.napcat

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import top.bilibili.config.NapCatConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NapCatClientRegressionTest {
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
}
