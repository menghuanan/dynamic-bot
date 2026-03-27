package top.bilibili.draw

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.client.j2se.MatrixToImageConfig
import com.google.zxing.client.j2se.MatrixToImageWriter
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.jetbrains.skia.*
import org.jetbrains.skiko.toBitmap
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager

val pointColor = 0xFF000000
val bgColor = 0xFFFFFFFF

/**
 * 生成登录二维码图片
 * 注意：返回的 Image 需要调用者负责关闭
 */
suspend fun loginQrCode(url: String): Image {
    return SkiaManager.executeDrawing {
        val bitMatrix = createLoginQrBitMatrix(url)
        val config = createLoginQrImageConfig()

        drawToImage(250, 250) {
            this@executeDrawing.drawLoginQrCanvas(this, bitMatrix, config)
        }
    }
}

/**
 * 直接生成登录二维码 PNG 字节，避免发送链路依赖本地临时文件再次读取。
 */
suspend fun loginQrCodeBytes(url: String): ByteArray {
    return SkiaManager.executeDrawing {
        val bitMatrix = createLoginQrBitMatrix(url)
        val config = createLoginQrImageConfig()

        drawToBytes(250, 250) {
            this@executeDrawing.drawLoginQrCanvas(this, bitMatrix, config)
        }
    }
}

/**
 * 登录二维码固定使用高纠错级别，保证中心 Logo 覆盖后仍能稳定扫码。
 */
private fun createLoginQrBitMatrix(url: String): BitMatrix {
    val qrCodeWriter = QRCodeWriter()
    return qrCodeWriter.encode(
        url,
        BarcodeFormat.QR_CODE,
        250,
        250,
        mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
        ),
    )
}

/**
 * 统一登录二维码的前景/背景色，确保 Image 与字节输出路径保持一致。
 */
private fun createLoginQrImageConfig(): MatrixToImageConfig {
    return MatrixToImageConfig(pointColor.toInt(), bgColor.toInt())
}

/**
 * 统一绘制登录二维码主体，避免 Image 输出和字节输出出现两套实现。
 */
private fun DrawingSession.drawLoginQrCanvas(canvas: Canvas, bitMatrix: BitMatrix, config: MatrixToImageConfig) {
    val qrBitmapImage = Image.makeFromBitmap(MatrixToImageWriter.toBufferedImage(bitMatrix, config).toBitmap())
    try {
        canvas.drawImage(qrBitmapImage, 0f, 0f)
    } finally {
        qrBitmapImage.close()
    }

    canvas.drawCircle(125f, 125f, 35f, createPaint {
        color = Color.WHITE
    })
    canvas.drawCircle(125f, 125f, 30f, createPaint {
        color = Color.makeRGB(2, 181, 218)
    })

    try {
        val svg = createSvg("icon/BILIBILI_LOGO.svg")
        if (svg != null) {
            val logoImg = svg.makeImage(this, 40f, 40f)
            canvas.drawImage(logoImg, 105f, 105f, createPaint {
                colorFilter = createBlendColorFilter(Color.WHITE, BlendMode.SRC_ATOP)
            })
        } else {
            drawFallbackLogo(this, canvas)
        }
    } catch (e: Exception) {
        logger.warn("加载二维码中心图标失败: ${e.message}")
        drawFallbackLogo(this, canvas)
    }
}

/**
 * 绘制备用 Logo（文字 "B"）
 */
private fun drawFallbackLogo(session: DrawingSession, canvas: Canvas) {
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
