package top.bilibili.draw

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.qrcode.QRCodeWriter
import org.jetbrains.skia.*
import org.jetbrains.skiko.toBitmap
import top.bilibili.skia.DrawingSession

val pointColor = 0xFF000000
val bgColor = 0xFFFFFFFF

/**
 * 生成登录二维码图片
 * 注意：返回的 Image 需要调用者负责关闭
 */
suspend fun loginQrCode(url: String): Image {
    return LoginQrCodeRenderer.renderImage(url)
}

/**
 * 直接生成登录二维码 PNG 字节，避免发送链路依赖本地临时文件再次读取。
 */
suspend fun loginQrCodeBytes(url: String): ByteArray {
    return LoginQrCodeRenderer.renderBytes(url)
}

/**
 * 绘制备用 Logo（文字 "B"）
 */
internal fun drawFallbackLogo(session: DrawingSession, canvas: Canvas) {
    val defaultTypeface = FontMgr.default.matchFamilyStyle("", FontStyle.NORMAL)
        ?: FontMgr.default.matchFamiliesStyle(arrayOf("sans-serif"), FontStyle.NORMAL)
        ?: return
    val fallbackFont = session.createFont(defaultTypeface, 50f)
    val textLine = session.createTextLine("B", fallbackFont)
    canvas.drawTextLine(textLine, 110f, 140f, session.createPaint {
        color = Color.WHITE
    })
}

fun qrCode(session: DrawingSession, url: String, width: Int, color: Int): Image {
    val qrCodeWriter = QRCodeWriter()

    val bitMatrix = qrCodeWriter.encode(
        url, BarcodeFormat.QR_CODE, width, width,
        mapOf(
            EncodeHintType.MARGIN to 0
        )
    )

    val c = Color.getRGB(color)
    val cc = c[0] + c[1] + c[2]
    val ccc = if (cc > 382) {
        val hsb = rgb2hsb(c[0], c[1], c[2])
        hsb[1] = if (hsb[1] + 0.25f > 1f) 1f else hsb[1] + 0.25f
        val rgb = hsb2rgb(hsb[0], hsb[1], hsb[2])
        Color.makeRGB(rgb[0], rgb[1], rgb[2])
    } else {
        color
    }

    val config = MatrixToImageConfig(ccc, Color.makeARGB(0, 255, 255, 255))

    return with(session) {
        Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap()).track()
    }
}
