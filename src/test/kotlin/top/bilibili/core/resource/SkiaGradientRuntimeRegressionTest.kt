package top.bilibili.core.resource

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.draw.generateLinearGradient
import top.bilibili.draw.makeCardBg
import top.bilibili.draw.makeRGB
import top.bilibili.skia.SkiaManager
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SkiaGradientRuntimeRegressionTest {
    @BeforeTest
    fun setupRuntimeConfig() {
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(BiliConfigManager, BiliConfig())
    }

    @Test
    fun `repeated gradient draws should reflect the current color set`() = runBlocking {
        val scenarios = listOf(
            listOf("#ff0000", "#00ff00", "#0000ff"),
            listOf("#ffd700", "#00bfff", "#ff69b4"),
            listOf("#111111", "#777777", "#eeeeee"),
        ).map { colors -> colors.map { Color.makeRGB(it) } }

        var previousSignature: Int? = null

        scenarios.forEach { colors ->
            val image = renderGradient(colors)
            val expected = generateLinearGradient(colors)

            assertPixelClose(image, 4, 4, expected.first(), tolerance = 24)
            assertPixelClose(image, image.width - 5, image.height - 5, expected.last(), tolerance = 24)

            val signature = image.getRGB(4, 4) xor image.getRGB(image.width - 5, image.height - 5)
            previousSignature?.let {
                assertNotEquals(it, signature, "new color input should not reuse the previous render result")
            }
            previousSignature = signature
        }
    }

    @Test
    fun `repeated skia gradient draws should survive cleanup cycle`() = runBlocking {
        val before = SkiaManager.getStatus()

        repeat(24) { index ->
            val colors = listOf(
                Color.makeRGB(indexColor(index, 0)),
                Color.makeRGB(indexColor(index, 1)),
                Color.makeRGB(indexColor(index, 2)),
                Color.makeRGB(indexColor(index, 3)),
            )

            val image = renderGradient(colors)
            val expected = generateLinearGradient(colors)
            assertPixelClose(image, 4, 4, expected.first(), tolerance = 24)
            assertPixelClose(image, image.width - 5, image.height - 5, expected.last(), tolerance = 24)
        }

        SkiaManager.performCleanup()
        val after = SkiaManager.getStatus()

        assertTrue(
            after.totalDrawingCount >= before.totalDrawingCount + 24,
            "draw loop should execute the expected number of real Skia drawing sessions",
        )
        assertTrue(
            after.totalCleanupCount >= before.totalCleanupCount + 1,
            "cleanup cycle should complete after repeated draw workload",
        )
    }

    private suspend fun renderGradient(colors: List<Int>): BufferedImage {
        val bytes = SkiaManager.executeDrawing {
            val image = makeCardBg(this, height = 96, colors = colors) {}
            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("failed to encode Skia gradient snapshot")
            try {
                data.bytes
            } finally {
                data.close()
            }
        }

        return ImageIO.read(ByteArrayInputStream(bytes))
            ?: error("failed to decode generated gradient PNG")
    }

    private fun assertPixelClose(
        image: BufferedImage,
        x: Int,
        y: Int,
        expectedColor: Int,
        tolerance: Int,
    ) {
        val actual = image.getRGB(x, y)
        val actualRgb = intArrayOf(actual shr 16 and 0xFF, actual shr 8 and 0xFF, actual and 0xFF)
        val expectedRgb = intArrayOf(Color.getR(expectedColor), Color.getG(expectedColor), Color.getB(expectedColor))

        assertTrue(
            actualRgb.indices.all { abs(actualRgb[it] - expectedRgb[it]) <= tolerance },
            "pixel ($x,$y) expected ${expectedRgb.contentToString()} but was ${actualRgb.contentToString()}",
        )
    }

    private fun indexColor(index: Int, offset: Int): String {
        val seed = (index * 53 + offset * 97) % 256
        val r = (seed + 40) % 256
        val g = (seed * 3 + 80) % 256
        val b = (seed * 5 + 120) % 256
        return "#%02x%02x%02x".format(r, g, b)
    }
}
