package top.bilibili.service

import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigServiceFeatureTest {
    private val subject = "group:10001"

    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.filter.clear()
        BiliData.atAll.clear()
        BiliData.dynamicPushTemplate.clear()
        BiliData.livePushTemplate.clear()
        BiliData.liveCloseTemplate.clear()
    }

    @Test
    fun `config overview should include runtime actionable summary sections`() {
        BiliData.dynamic[123456L] = SubData(name = "测试UP", contacts = mutableSetOf(subject))

        val overview = ConfigService.configOverview(uid = 123456L, subject = subject)

        assertTrue(overview.contains("模板:"))
        assertTrue(overview.contains("At全体:"))
        assertTrue(overview.contains("过滤器摘要:"), "missing filter summary section")
        assertTrue(overview.contains("订阅摘要:"), "missing subscription summary section")
        assertTrue(!overview.contains("可用命令:"), "user-side config overview should not include command catalog")
        assertTrue(!overview.contains("番剧订阅命中"), "uid scoped overview should not include bangumi aggregate")
    }
}
