package top.bilibili.draw

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import top.bilibili.core.resource.SkiaDrawSceneFixtures
import top.bilibili.data.ModuleAuthor
import top.bilibili.skia.SkiaManager
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AuthorSignatureTemplateRegressionTest {
    @BeforeTest
    fun setup() = runBlocking {
        SkiaDrawSceneFixtures.prepareEnvironment()
    }

    @Test
    fun `author header should ignore trailing line breaks in single-line signature render`() = runBlocking {
        val author = ModuleAuthor(
            mid = 10001L,
            name = "SceneAuthor",
            face = "cache/scene-user-face.png",
        )

        val baseline = renderAuthorHeader(author, "签名文本")
        val trailingLineBreak = renderAuthorHeader(author, "签名文本\n")

        assertSameImage(baseline, trailingLineBreak)
    }

    /**
     * 复用作者头绘制路径生成位图，确保回归测试覆盖真实的单行签名渲染实现。
     */
    private suspend fun renderAuthorHeader(author: ModuleAuthor, sign: String): BufferedImage {
        val bytes = SkiaManager.executeDrawing {
            val image = author.drawGeneral(this, sign, "https://example.com/u/1", 0x33AADD.toInt())
            val data = image.encodeToData(EncodedImageFormat.PNG) ?: error("failed to encode author header image")
            try {
                data.bytes
            } finally {
                data.close()
            }
        }

        return ImageIO.read(ByteArrayInputStream(bytes)) ?: error("failed to decode rendered author header")
    }

    /**
     * 逐像素比对两张渲染图，避免只断言字符串而漏掉真实绘图回归。
     */
    private fun assertSameImage(expected: BufferedImage, actual: BufferedImage) {
        assertEquals(expected.width, actual.width, "rendered author header width should stay stable")
        assertEquals(expected.height, actual.height, "rendered author header height should stay stable")

        val expectedPixels = expected.getRGB(0, 0, expected.width, expected.height, null, 0, expected.width)
        val actualPixels = actual.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width)
        assertContentEquals(expectedPixels, actualPixels, "trailing signature line breaks should not alter rendered pixels")
    }
}
