package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ColorCommandFeatureTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
    }

    @Test
    fun `setColor should reject unsubscribed uid and keep data unchanged`() = runBlocking {
        val result = DynamicService.setColor(114514L, "#d3edfa")
        assertEquals("没有订阅过 UID: 114514", result)
        assertTrue(!BiliData.dynamic.containsKey(114514L))
    }

    @Test
    fun `setColor should reject invalid hex to avoid draw failure`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP")
        val result = DynamicService.setColor(123L, "#zzzzzz")
        assertTrue(result.contains("格式错误"), "invalid hex should be rejected before persisting")
    }

    @Test
    fun `setColor should reject trailing separator to avoid empty color segment`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP")
        val result = DynamicService.setColor(123L, "#d3edfa;")
        assertTrue(result.contains("格式错误"), "trailing separator should be rejected")
    }
}
