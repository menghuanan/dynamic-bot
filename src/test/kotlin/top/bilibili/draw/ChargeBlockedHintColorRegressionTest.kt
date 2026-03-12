package top.bilibili.draw

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ChargeBlockedHintColorRegressionTest {
    @Test
    fun `charge blocked hint should use muted gray text color`() {
        val draw = Files.readString(
            Path.of("src/main/kotlin/top/bilibili/draw/DynamicMajorDraw.kt"),
            StandardCharsets.UTF_8
        )

        val blockedFunction = Regex(
            """suspend fun ModuleDynamic\.Major\.Blocked\.drawGeneral\(session: DrawingSession\): Image \{[\s\S]*?Color\.makeRGB\(\"#9499A0\"\)"""
        )

        assertTrue(
            blockedFunction.containsMatchIn(draw),
            "charge blocked hint should render with #9499A0 text color",
        )
    }
}