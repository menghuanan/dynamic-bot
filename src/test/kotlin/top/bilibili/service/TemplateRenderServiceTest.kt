package top.bilibili.service

import kotlinx.coroutines.runBlocking
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
            name = "模板测试UP",
            type = DynamicType.DYNAMIC_TYPE_AV,
            time = "2026-03-06 12:00:00",
            timestamp = 1772788800,
            content = "模板测试内容",
            images = emptyList(),
            links = emptyList(),
            drawPath = null,
            contact = null,
        )

        val segments = TemplateRenderService.buildSegments(
            message = message,
            contactStr = "group:10001",
            overrideTemplate = "{name}@{type}\n{link}\n{content}"
        )

        val mergedText = segments
            .filter { it.type == "text" }
            .joinToString("\n") { it.data["text"].orEmpty() }

        assertTrue(mergedText.contains("模板测试UP@投稿视频"))
        assertTrue(mergedText.contains("https://t.bilibili.com/200"))
        assertTrue(mergedText.contains("模板测试内容"))
    }
    @Test
    fun `draw-only template should downgrade to text when draw is unavailable`() = runBlocking {
        val message = DynamicMessage(
            did = "300",
            mid = 123456L,
            name = "模板测试UP",
            type = DynamicType.DYNAMIC_TYPE_AV,
            time = "2026-03-16 12:00:00",
            timestamp = 1773652800,
            content = "模板测试内容",
            images = emptyList(),
            links = emptyList(),
            drawPath = null,
            contact = null,
        )

        val segments = TemplateRenderService.buildSegments(
            message = message,
            contactStr = "group:10001",
            overrideTemplate = "{draw}"
        )

        val mergedText = segments
            .filter { it.type == "text" }
            .joinToString("\n") { it.data["text"].orEmpty() }

        assertTrue(mergedText.contains("模板测试UP"))
        assertTrue(mergedText.contains("https://t.bilibili.com/300"))
    }
}
