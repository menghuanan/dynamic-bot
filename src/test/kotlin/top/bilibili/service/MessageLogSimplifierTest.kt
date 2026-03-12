package top.bilibili.service

import top.bilibili.napcat.MessageSegment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MessageLogSimplifierTest {
    @Test
    fun `raw cq placeholders should cover extended napcat types`() {
        val simplified = MessageLogSimplifier.simplifyIncomingRaw(
            "[CQ:image,file=a.png][CQ:poke,qq=1][CQ:dice][CQ:markdown,data=hi][CQ:unknownx]"
        ) { error("should not truncate in this test") }

        assertEquals(
            "[\u56fe\u7247][\u6233\u4e00\u6233][\u9ab0\u5b50][Markdown][\u672a\u77e5\u7c7b\u578b:unknownx]",
            simplified
        )
    }

    @Test
    fun `segment placeholders should cover outgoing message types`() {
        val simplified = MessageLogSimplifier.simplifySegments(
            listOf(
                MessageSegment.text("hello"),
                MessageSegment.image("file:///a.png"),
                MessageSegment("at", mapOf("qq" to "all")),
                MessageSegment("lightapp", mapOf("data" to "{}")),
                MessageSegment("contact", mapOf("type" to "group"))
            )
        )

        assertEquals(
            "hello[\u56fe\u7247][@\u5168\u4f53][\u8f7b\u5e94\u7528][\u8054\u7cfb\u4eba]",
            simplified
        )
    }

    @Test
    fun `raw simplification should truncate oversized input`() {
        var warnedLength = 0
        val onTooLong: (Int) -> Unit = { warnedLength = it }
        val payload = "[CQ:share,url=https://example.com]" + "a".repeat(20_000)

        val simplified = MessageLogSimplifier.simplifyIncomingRaw(payload, onTooLong)

        assertEquals(20_034, warnedLength)
        assertTrue(simplified.endsWith("..."))
    }
}
