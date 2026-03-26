package top.bilibili.service

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.SubData

class ConfigServiceFeatureTest {
    private val subject = "group:10001"
    private val persistedSubject = "onebot11:group:10001"

    @BeforeTest
    fun setupRuntimeConfig() {
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(BiliConfigManager, BiliConfig())
    }

    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicColorByUid.clear()
        BiliData.filter.clear()
        BiliData.atAll.clear()
        BiliData.dynamicPushTemplate.clear()
        BiliData.livePushTemplate.clear()
        BiliData.liveCloseTemplate.clear()
    }

    @Test
    fun `配置概览应包含当前目标和 UID`() {
        val uid = 123456L
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))

        val overview = ConfigService.configOverview(uid = uid, subject = subject)

        assertTrue(overview.isNotBlank())
        assertTrue(overview.contains(subject))
        assertTrue(overview.contains(uid.toString()))
    }

    @Test
    fun `配置概览应展示解析后的目标作用域主题色`() {
        val uid = 123456L
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))
        BiliData.dynamicColorByUid[persistedSubject] = mutableMapOf(uid to "#d3edfa")

        val expectedColor = DynamicService.resolveColor(uid, subject)
        val overview = ConfigService.configOverview(uid = uid, subject = subject)

        assertTrue(overview.contains(expectedColor), "配置概览应显示目标作用域主题色绑定")
    }

    @Test
    fun `配置概览应展示命令绑定后的规范化颜色`() = runBlocking {
        val uid = 999L
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))

        val result: Any = DynamicService.setColor(uid, subject, "#FF0000")
        val normalizedColor = result.readProperty<String>("normalizedColor")

        val overview = ConfigService.configOverview(uid = uid, subject = subject)

        assertEquals(normalizedColor, BiliData.dynamicColorByUid[persistedSubject]?.get(uid))
        assertEquals(normalizedColor, DynamicService.resolveColor(uid, subject))
        assertTrue(overview.contains(normalizedColor))
    }

    private fun <T> Any.readProperty(name: String): T {
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        val getter = this.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        requireNotNull(getter) { "${this.javaClass.simpleName} 缺少属性: $name" }
        @Suppress("UNCHECKED_CAST")
        return getter.invoke(this) as T
    }
}
