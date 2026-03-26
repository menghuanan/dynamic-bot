package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.connector.OutgoingPart
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import kotlin.test.Test
import kotlin.test.assertTrue

class TemplateRenderServiceTest {
    @Test
    fun `preview renderer should use send pipeline placeholder behavior`() = runBlocking {
        val message = DynamicMessage(
            did = "200",
            mid = 123456L,
            name = "妯℃澘娴嬭瘯UP",
            type = DynamicType.DYNAMIC_TYPE_AV,
            time = "2026-03-06 12:00:00",
            timestamp = 1772788800,
            content = "妯℃澘娴嬭瘯鍐呭",
            images = emptyList(),
            links = emptyList(),
            drawPath = null,
            contact = null,
        )

        val segments = TemplateRenderService.buildSegments(
            message = message,
            contactStr = "group:10001",
            overrideTemplate = "{name}@{type}\n{link}\n{content}",
        )

        val mergedText = segments
            .filterIsInstance<OutgoingPart.Text>()
            .joinToString("\n") { it.text }

        assertTrue(mergedText.contains(message.name))
        assertTrue(mergedText.contains("https://t.bilibili.com/200"))
        assertTrue(mergedText.contains(message.content))
    }

    @Test
    fun `draw-only template should downgrade to text when draw is unavailable`() = runBlocking {
        val message = DynamicMessage(
            did = "300",
            mid = 123456L,
            name = "妯℃澘娴嬭瘯UP",
            type = DynamicType.DYNAMIC_TYPE_AV,
            time = "2026-03-16 12:00:00",
            timestamp = 1773652800,
            content = "妯℃澘娴嬭瘯鍐呭",
            images = emptyList(),
            links = emptyList(),
            drawPath = null,
            contact = null,
        )

        val segments = TemplateRenderService.buildSegments(
            message = message,
            contactStr = "group:10001",
            overrideTemplate = "{draw}",
        )

        val mergedText = segments
            .filterIsInstance<OutgoingPart.Text>()
            .joinToString("\n") { it.text }

        assertTrue(mergedText.contains("妯℃澘娴嬭瘯UP"))
        assertTrue(mergedText.contains("https://t.bilibili.com/300"))
    }
}
