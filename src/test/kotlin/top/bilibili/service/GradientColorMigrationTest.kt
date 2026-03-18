package top.bilibili.service

import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradientColorMigrationTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicColorByUid.clear()
    }

    @Test
    fun `startup migration should only canonicalize persisted syntax and keep original hue structure`() {
        BiliData.dynamic[1L] = SubData(name = "a")
        BiliData.dynamic[2L] = SubData(name = "b")
        BiliData.dynamicColorByUid["group:10001"] = mutableMapOf(
            1L to "#FDE5B6;#3183AE;#FFFFFF",
            2L to "#d3edfa",
        )

        val method = Class.forName("top.bilibili.service.GradientColorMigrationKt")
            .methods
            .firstOrNull { it.name == "migrateSubjectScopedColorBindings" }

        assertNotNull(method, "startup migration helper should exist")

        val summary = method.invoke(null)!!
        val scanned = summary.readProperty<Int>("scannedCount")
        val changed = summary.readProperty<Int>("changedCount")
        val unchanged = summary.readProperty<Int>("unchangedCount")

        assertEquals(2, scanned)
        assertEquals(1, changed, "migration should only canonicalize uppercase syntax in this fixture")
        assertEquals(scanned - changed, unchanged)
        assertTrue(
            summary.javaClass.methods.none { it.name == "getReasonCounts" },
            "syntax-only migration summary should not carry obsolete harmonization reason counts",
        )
        assertEquals("#fde5b6;#3183ae;#ffffff", BiliData.dynamicColorByUid["group:10001"]!![1L])
        assertEquals("#d3edfa", BiliData.dynamicColorByUid["group:10001"]!![2L])
    }

    private fun <T> Any.readProperty(name: String): T {
        val getterName = "get" + name.replaceFirstChar { it.uppercase() }
        val getter = this.javaClass.methods.firstOrNull { it.name == getterName && it.parameterCount == 0 }
        requireNotNull(getter) { "missing property: $name on ${this.javaClass.simpleName}" }
        @Suppress("UNCHECKED_CAST")
        return getter.invoke(this) as T
    }
}
