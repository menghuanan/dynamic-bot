package top.bilibili.service

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliData
import top.bilibili.SubData

class TemplateServiceFeatureTest {
    private val subject = "group:10001"
    private val persistedSubject = "onebot11:group:10001"
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
    fun `模板说明应仅保留面向用户的指引`() {
        val text = TemplateService.explainTemplate("d")
        assertFalse(text.contains("preview"))
        assertFalse(text.contains("gateway"))
    }

    @Test
    fun `setTemplate 应支持 UID 作用域绑定并持久化命名空间目标`() {
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))

        val result = TemplateService.setTemplate("d", "OneMsg", subject, uid)

        assertTrue(result.isNotBlank())
        assertEquals("OneMsg", BiliData.dynamicPushTemplateByUid[persistedSubject]?.get(uid))
    }

    @Test
    fun `setTemplate 应拒绝未订阅的 UID 绑定`() {
        val result = TemplateService.setTemplate("d", "OneMsg", subject, uid)
        assertTrue(result.isNotBlank())
        assertTrue(BiliData.dynamicPushTemplateByUid.isEmpty())
    }
}
