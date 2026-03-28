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

/**
 * 单次 Skia 绘制会话，统一追踪并释放本次绘制创建的原生资源。
 */
class DrawingSession : AutoCloseable {
    private val resources = Collections.synchronizedList(mutableListOf<AutoCloseable>())
    private val sessionId = UUID.randomUUID().toString().take(8)
    private val creationTime = System.currentTimeMillis()

    @Volatile
    private var isClosed = false

    private val logger = LoggerFactory.getLogger(DrawingSession::class.java)

    /**
     * 将可关闭资源纳入当前会话追踪。
     */
    fun <T : AutoCloseable> T.track(): T {
        if (!isClosed) {
            resources.add(this)
        }
        return this
    }

    /**
     * 创建并追踪 Surface。
     */
    fun createSurface(width: Int, height: Int): Surface {
        return Surface.makeRasterN32Premul(width, height).track()
    }

    /**
     * 创建并追踪图片对象。
     */
    fun createImage(bytes: ByteArray): Image {
        return Image.makeFromEncoded(bytes).track()
    }

    /**
     * 创建并追踪段落对象。
     */
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

    /**
     * 创建并追踪文本行对象。
     */
    fun createTextLine(text: String, font: Font): TextLine {
        return TextLine.make(text, font).track()
    }

    /**
     * 创建并追踪字体对象。
     */
    fun createFont(typeface: Typeface, size: Float): Font {
        return Font(typeface, size).track()
    }

    /**
     * 创建并追踪画笔对象。
     */
    fun createPaint(configure: Paint.() -> Unit = {}): Paint {
        return Paint().apply(configure).track()
    }

    /**
     * 创建并追踪线性渐变着色器。
     */
    fun createLinearGradient(start: Point, end: Point, colors: IntArray): Shader {
        return Shader.makeLinearGradient(start, end, colors).track()
    }

    /**
     * 创建并追踪扫描渐变着色器。
     */
    fun createSweepGradient(cx: Float, cy: Float, colors: IntArray): Shader {
        return Shader.makeSweepGradient(cx, cy, colors).track()
    }

    /**
     * 创建并追踪颜色混合滤镜。
     */
    fun createBlendColorFilter(color: Int, mode: BlendMode): ColorFilter {
        return ColorFilter.makeBlend(color, mode).track()
    }

    /**
     * 加载并追踪 SVG 资源。
     */
    fun createSvg(path: String): SVGDOM? {
        return loadSVG(path)?.track()
    }

    /**
     * 将绘制结果直接编码为字节数组。
     */
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
            // 编码产物只需要字节副本，Data 对象必须在本次会话内立即释放。
            data.close()
        }
    }

    /**
     * 将绘制结果直接返回为图片对象。
     */
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

    /**
     * 关闭当前绘制会话并按逆序释放所有追踪资源。
     */
    override fun close() {
        if (isClosed) return
        isClosed = true

        val duration = System.currentTimeMillis() - creationTime
        val resourceCount = resources.size

        // 逆序释放更接近资源的创建依赖链，能减少仍被上层对象引用时的关闭异常。
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
