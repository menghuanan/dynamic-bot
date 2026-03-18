package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class ColorGradientSafetyTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicColorByUid.clear()
    }

    @Test
    fun `setColor should reject excessive gradient stops to protect draw stability`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "test-up")
        val excessiveGradient = listOf(
            "#111111",
            "#222222",
            "#333333",
            "#444444",
            "#555555",
        ).joinToString(";")

        val result: Any = DynamicService.setColor(123L, "group:10001", excessiveGradient)

        assertTrue(
            result.readProperty<String>("message").contains("4"),
            "gradient stop count above the supported safety limit should be rejected before persisting",
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
