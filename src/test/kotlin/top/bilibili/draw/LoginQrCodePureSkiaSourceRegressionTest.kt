package top.bilibili.draw

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoginQrCodePureSkiaSourceRegressionTest {
    // 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `login qr renderer should avoid buffered image conversion`() {
        val rendererPath = Path.of("src/main/kotlin/top/bilibili/draw/LoginQrCodeRenderer.kt")
        val source = if (Files.exists(rendererPath)) {
            read(rendererPath.toString())
        } else {
            read("src/main/kotlin/top/bilibili/draw/QrCodeDraw.kt")
        }

        // 登录二维码渲染路径必须彻底摆脱 ZXing BufferedImage 转换，避免重新引入 AWT 依赖。
        assertFalse(
            source.contains("MatrixToImageWriter.toBufferedImage"),
            "login qr renderer should draw BitMatrix directly on Skia canvas instead of converting through BufferedImage",
        )
        // 最终实现仍应保留二维码矩阵来源，避免测试放过“去掉二维码生成”这种假阳性修改。
        assertTrue(
            source.contains("BitMatrix"),
            "login qr renderer should keep using ZXing BitMatrix as the QR matrix source",
        )
    }
}
