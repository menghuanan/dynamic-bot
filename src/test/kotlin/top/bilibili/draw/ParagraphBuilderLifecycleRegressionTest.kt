package top.bilibili.draw

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ParagraphBuilderLifecycleRegressionTest {
    // 统一按 UTF-8 读取源码，避免 Windows 默认编码影响回归测试结论。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `shared skia paragraph helpers should close paragraph builder explicitly`() {
        val drawingSessionSource = read("src/main/kotlin/top/bilibili/skia/DrawingSession.kt")
        val generalSource = read("src/main/kotlin/top/bilibili/draw/General.kt")

        assertTrue(
            drawingSessionSource.contains("builder.close()"),
            "DrawingSession.createParagraph should close ParagraphBuilder explicitly",
        )
        assertTrue(
            generalSource.contains("builder.close()"),
            "General paragraph helpers should close ParagraphBuilder explicitly",
        )
    }

    @Test
    fun `core draw scenes should not keep direct paragraph builder chains`() {
        val dynamicDrawSource = read("src/main/kotlin/top/bilibili/draw/DynamicDraw.kt")
        val dynamicMajorDrawSource = read("src/main/kotlin/top/bilibili/draw/DynamicMajorDraw.kt")
        val dynamicModuleDrawSource = read("src/main/kotlin/top/bilibili/draw/DynamicModuleDraw.kt")
        val liveDrawSource = read("src/main/kotlin/top/bilibili/draw/LiveDraw.kt")

        assertFalse(
            dynamicDrawSource.contains("ParagraphBuilder("),
            "DynamicDraw should use shared paragraph helpers instead of direct builder chains",
        )
        assertFalse(
            dynamicMajorDrawSource.contains("ParagraphBuilder("),
            "DynamicMajorDraw should use shared paragraph helpers instead of direct builder chains",
        )
        assertFalse(
            dynamicModuleDrawSource.contains("ParagraphBuilder("),
            "DynamicModuleDraw should use shared paragraph helpers instead of direct builder chains",
        )
        assertFalse(
            liveDrawSource.contains("ParagraphBuilder("),
            "LiveDraw should use shared paragraph helpers instead of direct builder chains",
        )
    }
}
