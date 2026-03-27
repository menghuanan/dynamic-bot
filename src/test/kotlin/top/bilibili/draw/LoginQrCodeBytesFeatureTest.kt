package top.bilibili.draw

import kotlinx.coroutines.runBlocking
import top.bilibili.core.resource.SkiaDrawSceneFixtures
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class LoginQrCodeBytesFeatureTest {
    @BeforeTest
    fun setup() = runBlocking {
        // 统一复用 Skia 场景基线初始化，确保测试环境与绘图回归测试一致。
        SkiaDrawSceneFixtures.prepareEnvironment()
    }

    @Test
    fun `login qr code bytes should produce non-empty png payload`() = runBlocking {
        val bytes = loginQrCodeBytes("https://example.com/login")

        // PNG 文件头固定为 8 字节签名，足以证明输出不是空字节或非 PNG 数据。
        assertTrue(bytes.size > 8, "login qr code bytes should contain a complete PNG payload")
        assertContentEquals(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            bytes.copyOfRange(0, 8),
            "login qr code bytes should start with the PNG signature",
        )
    }
}
