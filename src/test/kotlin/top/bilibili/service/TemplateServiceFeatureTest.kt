package top.bilibili.service

import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateServiceFeatureTest {
    private val subject = "group:10001"
    private val uid = 123456L

    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicPushTemplate.clear()
        BiliData.livePushTemplate.clear()
        BiliData.liveCloseTemplate.clear()
        BiliData.dynamicPushTemplateByUid.clear()
        BiliData.livePushTemplateByUid.clear()
        BiliData.liveCloseTemplateByUid.clear()
    }

    @Test
    fun `template explain should keep only user-facing guidance`() {
        val text = TemplateService.explainTemplate("d")
        assertFalse(text.contains("不改变: 绘图引擎本身、发送重试/队列/网关语义。"))
        assertFalse(text.contains("提示: preview 已复用实发渲染链，可用于上线前验证模板效果。"))
    }

    @Test
    fun `setTemplate should support uid scoped binding and persist map`() {
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(subject))

        val result = TemplateService.setTemplate("d", "OneMsg", subject, uid)
        assertEquals("配置完成", result)
        assertEquals("OneMsg", BiliData.dynamicPushTemplateByUid[subject]?.get(uid))
    }

    @Test
    fun `setTemplate should reject unsubscribed uid binding`() {
        val result = TemplateService.setTemplate("d", "OneMsg", subject, uid)
        assertTrue(result.contains("未订阅"), "uid binding should require group subscription")
    }
}

