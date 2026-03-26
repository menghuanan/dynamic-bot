package top.bilibili.service

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.bilibili.BiliData
import top.bilibili.SubData

class ColorCommandFeatureTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicColorByUid.clear()
    }

    @Test
    fun `setColor 应拒绝未订阅 UID 且保持数据不变`() = runBlocking {
        val result: Any = DynamicService.setColor(114514L, "group:10001", "#d3edfa")
        assertEquals("ColorBindingResult", result::class.simpleName)
        assertTrue(result.readProperty<String>("message").contains("114514"))
        assertTrue(!BiliData.dynamic.containsKey(114514L))
    }

    @Test
    fun `setColor 应拒绝非法十六进制颜色以避免绘图失败`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP")
        val result: Any = DynamicService.setColor(123L, "group:10001", "#zzzzzz")
        assertEquals("ColorBindingResult", result::class.simpleName)
        assertTrue(result.readProperty<String>("message").isNotBlank())
    }

    @Test
    fun `setColor 应拒绝尾部分隔符以避免空颜色片段`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP")
        val result: Any = DynamicService.setColor(123L, "group:10001", "#d3edfa;")
        assertEquals("ColorBindingResult", result::class.simpleName)
        assertTrue(result.readProperty<String>("message").isNotBlank())
    }

    @Test
    fun `setColor 应在带命名空间的持久化目标之间隔离同一 UID`() = runBlocking {
        val uid = 123L
        BiliData.dynamic[uid] = SubData(name = "测试UP")

        val groupA: Any = DynamicService.setColor(uid, "group:10001", "#d3edfa")
        val groupB: Any = DynamicService.setColor(uid, "group:10002", "#fde8ed")

        assertEquals("ColorBindingResult", groupA::class.simpleName)
        assertEquals("ColorBindingResult", groupB::class.simpleName)
        val normalizedA = groupA.readProperty<String>("normalizedColor")
        val normalizedB = groupB.readProperty<String>("normalizedColor")

        assertEquals(normalizedA, BiliData.dynamicColorByUid["onebot11:group:10001"]?.get(uid))
        assertEquals(normalizedB, BiliData.dynamicColorByUid["onebot11:group:10002"]?.get(uid))
        assertNotEquals(
            BiliData.dynamicColorByUid["onebot11:group:10001"]?.get(uid),
            BiliData.dynamicColorByUid["onebot11:group:10002"]?.get(uid),
        )
    }

    @Test
    fun `setColor 应只规范化语法而不改变业务配色`() = runBlocking {
        val uid = 456L
        val rawInput = "#FDE5B6;#3183AE;#FFFFFF"
        BiliData.dynamic[uid] = SubData(name = "测试UP")

        val result: Any = DynamicService.setColor(uid, "group:10001", rawInput)

        assertEquals("ColorBindingResult", result::class.simpleName)
        val normalizedColor = result.readProperty<String>("normalizedColor")
        val message = result.readProperty<String>("message")
        val changed = result.readProperty<Boolean>("changed")
        val persisted = BiliData.dynamicColorByUid["onebot11:group:10001"]?.get(uid)

        assertNotNull(persisted)
        assertEquals(normalizedColor, persisted)
        assertEquals("#fde5b6;#3183ae;#ffffff", normalizedColor)
        assertTrue(normalizedColor == normalizedColor.lowercase(), "规范化后的颜色应为小写")
        assertNotEquals(rawInput, normalizedColor, "规范化应修正原始大写输入")
        assertTrue(changed, "规范化应报告存储值已变更")
        assertTrue(message.contains(rawInput), "仅语法规范化时仍应展示前后对比")
        assertTrue(message.contains(normalizedColor), "面向用户的消息应包含规范化后的颜色")
        assertTrue(
            result.javaClass.methods.none { it.name == "getReasons" },
            "运行时已不再产生旧的协调原因后，命令结果不应再暴露该字段",
        )
    }

    private fun <T> Any.readProperty(name: String): T {
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        val getter = this.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        requireNotNull(getter) { "${this.javaClass.simpleName} 缺少属性: $name" }
        @Suppress("UNCHECKED_CAST")
        return getter.invoke(this) as T
    }
}
