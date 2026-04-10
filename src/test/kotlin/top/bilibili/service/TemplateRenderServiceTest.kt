package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.connector.OutgoingPart
import top.bilibili.BiliData
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class TemplateRenderServiceTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamicPushTemplate.clear()
        BiliData.dynamicPushTemplateByUid.clear()
    }

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

    @Test
    fun `explicit template content should override legacy binding lookup`() = runBlocking {
        val message = DynamicMessage(
            did = "400",
            mid = 123456L,
            name = "显式模板UP",
            type = DynamicType.DYNAMIC_TYPE_AV,
            time = "2026-03-18 12:00:00",
            timestamp = 1773825600,
            content = "显式模板正文",
            images = emptyList(),
            links = emptyList(),
            drawPath = null,
            contact = null,
        )
        BiliData.dynamicPushTemplateByUid["onebot11:group:10001"] = mutableMapOf(123456L to "TextOnly")

        val segments = TemplateRenderService.buildSegments(
            message = message,
            contactStr = "onebot11:group:10001",
            overrideTemplate = "{name}-custom-{content}",
        )

        val mergedText = segments
            .filterIsInstance<OutgoingPart.Text>()
            .joinToString("\n") { it.text }

        assertTrue(mergedText.contains("显式模板UP-custom-显式模板正文"))
        assertTrue(!mergedText.contains("https://t.bilibili.com/400"))
    }
}
