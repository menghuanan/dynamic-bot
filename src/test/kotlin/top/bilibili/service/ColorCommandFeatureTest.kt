package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ColorCommandFeatureTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicColorByUid.clear()
    }

    @Test
    fun `setColor should reject unsubscribed uid and keep data unchanged`() = runBlocking {
        val result: Any = DynamicService.setColor(114514L, "group:10001", "#d3edfa")
        assertEquals("ColorBindingResult", result::class.simpleName)
        assertTrue(result.readProperty<String>("message").contains("114514"))
        assertTrue(!BiliData.dynamic.containsKey(114514L))
    }

    @Test
    fun `setColor should reject invalid hex to avoid draw failure`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "test-up")
        val result: Any = DynamicService.setColor(123L, "group:10001", "#zzzzzz")
        assertEquals("ColorBindingResult", result::class.simpleName)
        assertTrue(
            result.readProperty<String>("message").contains("格式错误"),
            "invalid hex should be rejected before persisting",
        )
    }

    @Test
    fun `setColor should reject trailing separator to avoid empty color segment`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "test-up")
        val result: Any = DynamicService.setColor(123L, "group:10001", "#d3edfa;")
        assertEquals("ColorBindingResult", result::class.simpleName)
        assertTrue(result.readProperty<String>("message").contains("格式错误"), "trailing separator should be rejected")
    }

    @Test
    fun `setColor should isolate the same uid across subjects`() = runBlocking {
        val uid = 123L
        BiliData.dynamic[uid] = SubData(name = "test-up")

        val groupA: Any = DynamicService.setColor(uid, "group:10001", "#d3edfa")
        val groupB: Any = DynamicService.setColor(uid, "group:10002", "#fde8ed")

        assertEquals("ColorBindingResult", groupA::class.simpleName)
        assertEquals("ColorBindingResult", groupB::class.simpleName)
        val normalizedA = groupA.readProperty<String>("normalizedColor")
        val normalizedB = groupB.readProperty<String>("normalizedColor")

        assertEquals(normalizedA, BiliData.dynamicColorByUid["group:10001"]?.get(uid))
        assertEquals(normalizedB, BiliData.dynamicColorByUid["group:10002"]?.get(uid))
        assertNotEquals(BiliData.dynamicColorByUid["group:10001"]?.get(uid), BiliData.dynamicColorByUid["group:10002"]?.get(uid))
    }

    @Test
    fun `setColor should canonicalize syntax without business recoloring`() = runBlocking {
        val uid = 456L
        val rawInput = "#FDE5B6;#3183AE;#FFFFFF"
        BiliData.dynamic[uid] = SubData(name = "test-up")

        val result: Any = DynamicService.setColor(uid, "group:10001", rawInput)

        assertEquals("ColorBindingResult", result::class.simpleName)
        val normalizedColor = result.readProperty<String>("normalizedColor")
        val message = result.readProperty<String>("message")
        val changed = result.readProperty<Boolean>("changed")
        val persisted = BiliData.dynamicColorByUid["group:10001"]?.get(uid)

        assertNotNull(persisted)
        assertEquals(normalizedColor, persisted)
        assertEquals("#fde5b6;#3183ae;#ffffff", normalizedColor)
        assertTrue(normalizedColor == normalizedColor.lowercase(), "normalized color should be lower-case")
        assertNotEquals(rawInput, normalizedColor, "normalization should canonicalize raw uppercase input")
        assertTrue(changed, "canonicalization should report changed storage value")
        assertTrue(message.contains(rawInput), "syntax-only canonicalization should still show before and after")
        assertTrue(message.contains(normalizedColor), "user-facing message should include normalized color")
        assertTrue(
            result.javaClass.methods.none { it.name == "getReasons" },
            "command result should not expose obsolete harmonization reasons once the runtime no longer produces them",
        )
    }

    private fun <T> Any.readProperty(name: String): T {
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        val getter = this.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        requireNotNull(getter) { "missing property: $name on ${this.javaClass.simpleName}" }
        @Suppress("UNCHECKED_CAST")
        return getter.invoke(this) as T
    }
}
