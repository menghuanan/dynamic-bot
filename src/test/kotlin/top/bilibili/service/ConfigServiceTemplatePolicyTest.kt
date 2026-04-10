package top.bilibili.service

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.SubData
import top.bilibili.TemplatePolicy

class ConfigServiceTemplatePolicyTest {
    private val subject = "group:10001"
    private val persistedSubject = "onebot11:group:10001"
    private val uid = 123456L

    @BeforeTest
    fun setupRuntimeConfig() {
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(BiliConfigManager, BiliConfig())
    }

    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicTemplatePolicyByScope.clear()
        BiliData.group.clear()
    }

    @Test
    fun `config overview should show template policy summary`() {
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(persistedSubject),
            sourceRefs = mutableSetOf("direct:$persistedSubject"),
        )
        BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg", "TwoMsg"),
                randomEnabled = true,
            ),
        )

        val text = ConfigService.configOverview(uid = uid, subject = subject)

        assertTrue(text.contains("随机: 开启"))
        assertTrue(text.contains("OneMsg"))
        assertTrue(text.contains("TwoMsg"))
        assertFalse(text.contains("群自定义"))
    }
}
