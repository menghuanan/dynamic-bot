package top.bilibili.utils

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImagePreprocessPolicyTest {
    @Test
    fun `large static jpeg should be preprocessed only when both dimensions exceed threshold`() {
        val exactThreshold = inspectImageHeader(jpegBytes(width = 4000, height = 3000))
        val oversized = inspectImageHeader(jpegBytes(width = 4001, height = 3001))

        assertNotNull(exactThreshold)
        assertEquals(ImageHeaderFormat.JPEG, exactThreshold.format)
        assertFalse(shouldPreprocessLargeStaticImage(exactThreshold))

        assertNotNull(oversized)
        assertEquals(ImageHeaderFormat.JPEG, oversized.format)
        assertTrue(shouldPreprocessLargeStaticImage(oversized))
    }

    @Test
    fun `long png should stay exempt even when one side is very large`() {
        val tall = inspectImageHeader(pngBytes(width = 1800, height = 9000))
        val wide = inspectImageHeader(pngBytes(width = 9000, height = 1800))

        assertNotNull(tall)
        assertEquals(ImageHeaderFormat.PNG, tall.format)
        assertFalse(shouldPreprocessLargeStaticImage(tall))

        assertNotNull(wide)
        assertEquals(ImageHeaderFormat.PNG, wide.format)
        assertFalse(shouldPreprocessLargeStaticImage(wide))
    }

    @Test
    fun `animated gif should stay exempt even when both dimensions are large`() {
        val animatedGif = inspectImageHeader(animatedGifBytes(width = 5000, height = 4000))

        assertNotNull(animatedGif)
        assertEquals(ImageHeaderFormat.GIF, animatedGif.format)
        assertTrue(animatedGif.isAnimated)
        assertFalse(shouldPreprocessLargeStaticImage(animatedGif))
    }

    @Test
    fun `downscaled size should preserve aspect ratio and clamp long edge to 2800`() {
        assertEquals(2800 to 2100, computeDownscaledSize(width = 5712, height = 4284))
        assertEquals(2100 to 2800, computeDownscaledSize(width = 4284, height = 5712))
    }

    @Test
    fun `resized variant path should use stable sibling cache file`() {
        val original = Path.of("cache/images/example.jpg")
        val resized = resizedVariantPath(original)

        assertEquals(original.parent, resized.parent)
        assertEquals("resized_example.jpg", resized.fileName.toString())
    }

    private fun pngBytes(width: Int, height: Int): ByteArray {
        return byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52,
            ((width ushr 24) and 0xFF).toByte(),
            ((width ushr 16) and 0xFF).toByte(),
            ((width ushr 8) and 0xFF).toByte(),
            (width and 0xFF).toByte(),
            ((height ushr 24) and 0xFF).toByte(),
            ((height ushr 16) and 0xFF).toByte(),
            ((height ushr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(),
            0x08, 0x02, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
        )
    }

    private fun jpegBytes(width: Int, height: Int): ByteArray {
        return byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(),
            0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10,
            0x4A, 0x46, 0x49, 0x46, 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0xFF.toByte(), 0xC0.toByte(), 0x00, 0x11,
            0x08,
            ((height ushr 8) and 0xFF).toByte(),
            (height and 0xFF).toByte(),
            ((width ushr 8) and 0xFF).toByte(),
            (width and 0xFF).toByte(),
            0x03,
            0x01, 0x11, 0x00,
            0x02, 0x11, 0x00,
            0x03, 0x11, 0x00,
            0xFF.toByte(), 0xD9.toByte(),
        )
    }

    private fun animatedGifBytes(width: Int, height: Int): ByteArray {
        val widthLo = (width and 0xFF).toByte()
        val widthHi = ((width ushr 8) and 0xFF).toByte()
        val heightLo = (height and 0xFF).toByte()
        val heightHi = ((height ushr 8) and 0xFF).toByte()

        return byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61,
            widthLo, widthHi, heightLo, heightHi,
            0x00, 0x00, 0x00,
            0x21, 0xFF.toByte(), 0x0B, 0x4E, 0x45, 0x54, 0x53, 0x43, 0x41, 0x50, 0x45, 0x32, 0x2E, 0x30,
            0x03, 0x01, 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, widthLo, widthHi, heightLo, heightHi, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, widthLo, widthHi, heightLo, heightHi, 0x00,
            0x3B,
        )
    }
}
