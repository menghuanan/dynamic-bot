package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigServiceFeatureTest {
    private val subject = "group:10001"

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
    fun `config overview should include the current subject and uid`() {
        val uid = 123456L
        BiliData.dynamic[uid] = SubData(name = "test-up", contacts = mutableSetOf(subject))

        val overview = ConfigService.configOverview(uid = uid, subject = subject)

        assertTrue(overview.isNotBlank())
        assertTrue(overview.contains(subject))
        assertTrue(overview.contains(uid.toString()))
    }

    @Test
    fun `config overview should show resolved subject scoped custom color`() {
        val uid = 123456L
        BiliData.dynamic[uid] = SubData(name = "test-up", contacts = mutableSetOf(subject))
        BiliData.dynamicColorByUid[subject] = mutableMapOf(uid to "#d3edfa")

        val expectedColor = DynamicService.resolveColor(uid, subject)
        val overview = ConfigService.configOverview(uid = uid, subject = subject)

        assertTrue(overview.contains(expectedColor), "config overview should display subject color binding")
    }

    @Test
    fun `config overview should display normalized stored color after command binding`() = runBlocking {
        val uid = 999L
        BiliData.dynamic[uid] = SubData(name = "test-up", contacts = mutableSetOf(subject))

        val result: Any = DynamicService.setColor(uid, subject, "#FF0000")
        val normalizedColor = result.readProperty<String>("normalizedColor")

        val overview = ConfigService.configOverview(uid = uid, subject = subject)

        assertEquals(normalizedColor, BiliData.dynamicColorByUid[subject]?.get(uid))
        assertEquals(normalizedColor, DynamicService.resolveColor(uid, subject))
        assertTrue(overview.contains(normalizedColor))
    }

    private fun <T> Any.readProperty(name: String): T {
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        val getter = this.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        requireNotNull(getter) { "missing property: $name on ${this.javaClass.simpleName}" }
        @Suppress("UNCHECKED_CAST")
        return getter.invoke(this) as T
    }
}
