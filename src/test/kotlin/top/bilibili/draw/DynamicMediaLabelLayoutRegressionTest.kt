package top.bilibili.draw

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DynamicMediaLabelLayoutRegressionTest {
    @Test
    fun `dynamic media label should use centered text baseline`() {
        val draw = Files.readString(
            Path.of("src/main/kotlin/top/bilibili/draw/DynamicMajorDraw.kt"),
            StandardCharsets.UTF_8
        )

        assertTrue(
            draw.contains("dynamicMediaLabelTextBaseline(rrect, labelTextLine)"),
            "dynamic media labels should use the centered baseline helper",
        )
    }
}
