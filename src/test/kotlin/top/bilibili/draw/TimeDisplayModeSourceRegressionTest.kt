package top.bilibili.draw

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeDisplayModeSourceRegressionTest {
    /**
     * 统一换行后再断言源码片段，避免平台换行差异导致的伪失败。
     */
    private fun read(path: String): String =
        Files.readString(Path.of(path), StandardCharsets.UTF_8)
            .replace("\r\n", "\n")
            .replace('\r', '\n')

    @Test
    fun `draw paths should use unified display time helper instead of hardcoded relative time`() {
        val dynamicDraw = read("src/main/kotlin/top/bilibili/draw/DynamicDraw.kt")
        val liveDraw = read("src/main/kotlin/top/bilibili/draw/LiveDraw.kt")
        val resolveLink = read("src/main/kotlin/top/bilibili/service/ResolveLinkService.kt")

        assertTrue(dynamicDraw.contains("displayTime"))
        assertFalse(dynamicDraw.contains("formatRelativeTime"))

        assertTrue(liveDraw.contains("formatDisplayTime(TimeDisplayMode.ABSOLUTE)"))
        assertFalse(liveDraw.contains("formatRelativeTime"))

        assertTrue(resolveLink.contains("displayTime"))
        assertFalse(resolveLink.contains("formatRelativeTime"))
    }
}
