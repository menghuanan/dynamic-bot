package top.bilibili.draw

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager

/**
 * 登录二维码专用渲染器：统一负责矩阵生成、Skia 绘制和 PNG/Image 输出。
 */
object LoginQrCodeRenderer {
    private const val loginQrSize = 250

    /**
     * 返回登录二维码图片对象，供仍需要 Image 形式的调用方复用。
     */
    suspend fun renderImage(url: String): Image {
        return SkiaManager.executeDrawing {
            val bitMatrix = createLoginQrBitMatrix(url)
            drawToImage(loginQrSize, loginQrSize) {
                // 登录二维码直接在 Skia 画布上落点，避免 BufferedImage/AWT 中转。
                this@executeDrawing.drawLoginQrCanvas(this, bitMatrix)
            }
        }
    }

    /**
     * 返回登录二维码 PNG 字节，供消息发送链路直接持久化或缓存。
     */
    suspend fun renderBytes(url: String): ByteArray {
        return SkiaManager.executeDrawing {
            val bitMatrix = createLoginQrBitMatrix(url)
            drawToBytes(loginQrSize, loginQrSize) {
                // 字节输出与 Image 输出复用同一套绘制逻辑，避免两条路径出现像素差异。
                this@executeDrawing.drawLoginQrCanvas(this, bitMatrix)
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
            loginQrSize,
            loginQrSize,
            mapOf(
                EncodeHintType.MARGIN to 1,
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            ),
        )
    }

    /**
     * 统一绘制登录二维码主体，保证矩阵、遮罩圆和中心 Logo 始终共用同一入口。
     */
    private fun DrawingSession.drawLoginQrCanvas(canvas: Canvas, bitMatrix: BitMatrix) {
        // 先铺白底，再仅绘制黑色模块，避免遗留像素影响二维码边缘识别。
        canvas.clear(Color.WHITE)
        drawBitMatrix(canvas, bitMatrix)
        drawCenterDecorations(canvas)

        try {
            val svg = createSvg("icon/BILIBILI_LOGO.svg")
            if (svg != null) {
                val logoImg = svg.makeImage(this, 40f, 40f)
                canvas.drawImage(logoImg, 105f, 105f, createPaint {
                    colorFilter = createBlendColorFilter(Color.WHITE, org.jetbrains.skia.BlendMode.SRC_ATOP)
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
     * 逐个绘制二维码黑色模块，确保登录路径完全绕过 ZXing 的 BufferedImage 输出。
     */
    private fun DrawingSession.drawBitMatrix(canvas: Canvas, bitMatrix: BitMatrix) {
        val modulePaint = createPaint {
            color = Color.BLACK
            isAntiAlias = false
        }

        for (x in 0 until bitMatrix.width) {
            for (y in 0 until bitMatrix.height) {
                if (bitMatrix.get(x, y)) {
                    canvas.drawRect(Rect.makeXYWH(x.toFloat(), y.toFloat(), 1f, 1f), modulePaint)
                }
            }
        }
    }

    /**
     * 保留原有中心白色镂空与品牌色圆环，维持扫码容错和视觉表现。
     */
    private fun DrawingSession.drawCenterDecorations(canvas: Canvas) {
        canvas.drawCircle(125f, 125f, 35f, createPaint {
            color = Color.WHITE
        })
        canvas.drawCircle(125f, 125f, 30f, createPaint {
            color = Color.makeRGB(2, 181, 218)
        })
    }
}
