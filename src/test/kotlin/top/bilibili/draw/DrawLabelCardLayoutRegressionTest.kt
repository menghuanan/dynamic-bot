package top.bilibili.draw

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DrawLabelCardLayoutRegressionTest {
    @Test
    fun `drawLabelCard should use centered text baseline`() {
        val draw = Files.readString(
            Path.of("src/main/kotlin/top/bilibili/draw/DynamicDraw.kt"),
            StandardCharsets.UTF_8
        )

        assertTrue(
            draw.contains("labelCardTextBaseline(rrect, textLine)"),
            "drawLabelCard should use the centered baseline helper",
        )
    }
}
