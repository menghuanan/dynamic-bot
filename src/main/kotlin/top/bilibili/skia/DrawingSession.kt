package top.bilibili.skia

import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Point
import org.jetbrains.skia.Shader
import org.jetbrains.skia.Surface
import org.jetbrains.skia.TextLine
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.Image
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.svg.SVGDOM
import org.slf4j.LoggerFactory
import top.bilibili.draw.loadSVG
import java.util.Collections
import java.util.UUID

class DrawingSession : AutoCloseable {
    private val resources = Collections.synchronizedList(mutableListOf<AutoCloseable>())
    private val sessionId = UUID.randomUUID().toString().take(8)
    private val creationTime = System.currentTimeMillis()

    @Volatile
    private var isClosed = false

    private val logger = LoggerFactory.getLogger(DrawingSession::class.java)

    fun <T : AutoCloseable> T.track(): T {
        if (!isClosed) {
            resources.add(this)
        }
        return this
    }

    fun createSurface(width: Int, height: Int): Surface {
        return Surface.makeRasterN32Premul(width, height).track()
    }

    fun createImage(bytes: ByteArray): Image {
        val image = Image.makeFromEncoded(bytes)
            ?: throw IllegalArgumentException("Failed to decode image from bytes")
        return image.track()
    }

    fun createParagraph(
        style: ParagraphStyle,
        fonts: FontCollection,
        width: Float,
        build: ParagraphBuilder.() -> Unit,
    ): Paragraph {
        val builder = ParagraphBuilder(style, fonts)
        builder.apply(build)
        val paragraph = builder.build().layout(width)
        return paragraph.track()
    }

    fun createTextLine(text: String, font: Font): TextLine {
        return TextLine.make(text, font).track()
    }

    fun createFont(typeface: Typeface, size: Float): Font {
        return Font(typeface, size).track()
    }

    fun createPaint(configure: Paint.() -> Unit = {}): Paint {
        return Paint().apply(configure).track()
    }

    fun createLinearGradient(start: Point, end: Point, colors: IntArray): Shader {
        return Shader.makeLinearGradient(start, end, colors).track()
    }

    fun createSweepGradient(cx: Float, cy: Float, colors: IntArray): Shader {
        return Shader.makeSweepGradient(cx, cy, colors).track()
    }

    fun createBlendColorFilter(color: Int, mode: BlendMode): ColorFilter {
        return ColorFilter.makeBlend(color, mode).track()
    }

    fun createSvg(path: String): SVGDOM? {
        return loadSVG(path)?.track()
    }

    inline fun drawToBytes(
        width: Int,
        height: Int,
        format: EncodedImageFormat = EncodedImageFormat.PNG,
        crossinline draw: Canvas.() -> Unit,
    ): ByteArray {
        val surface = createSurface(width, height)
        surface.canvas.draw()
        val image = surface.makeImageSnapshot().track()
        val data = image.encodeToData(format)
            ?: throw IllegalStateException("Failed to encode image")
        return try {
            data.bytes
        } finally {
            data.close()
        }
    }

    inline fun drawToImage(
        width: Int,
        height: Int,
        crossinline draw: Canvas.() -> Unit,
    ): Image {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.draw()
            return surface.makeImageSnapshot()
        } finally {
            surface.close()
        }
    }

    override fun close() {
        if (isClosed) return
        isClosed = true

        val duration = System.currentTimeMillis() - creationTime
        val resourceCount = resources.size

        resources.asReversed().forEach { resource ->
            runCatching {
                if (resource is org.jetbrains.skia.impl.Managed && resource.isClosed) {
                    return@forEach
                }
                resource.close()
            }.onFailure { e ->
                logger.warn("关闭资源失败: ${resource::class.simpleName}", e)
            }
        }
        resources.clear()

        logger.debug("DrawingSession[$sessionId] 关闭，释放 $resourceCount 个资源，耗时 ${duration}ms")
    }
}