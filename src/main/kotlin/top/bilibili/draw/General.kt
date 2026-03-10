package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.svg.SVGDOM
import top.bilibili.BiliConfigManager
import top.bilibili.data.Theme
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.loadResourceBytes
import java.io.File
import java.util.*

// ==================== Image 安全访问扩展 ====================

/**
 * 安全获取 Image 的宽度
 * 使用 Managed.isClosed 检查 native 资源是否已释放
 * @return 图片宽度，如果 Image 无效则返回 0
 */
fun Image.safeWidth(): Int {
    return try {
        if (this.isClosed) return 0
        this.width
    } catch (e: Exception) {
        logger.warn("获取 Image 宽度失败: ${e.message}")
        0
    }
}

/**
 * 安全获取 Image 的高度
 * @return 图片高度，如果 Image 无效则返回 0
 */
fun Image.safeHeight(): Int {
    return try {
        if (this.isClosed) return 0
        this.height
    } catch (e: Exception) {
        logger.warn("获取 Image 高度失败: ${e.message}")
        0
    }
}

/**
 * 检查 Image 是否有效（未关闭且尺寸有效）
 */
fun Image.isValid(): Boolean {
    return try {
        !this.isClosed && this.width > 0 && this.height > 0
    } catch (e: Exception) {
        false
    }
}

// ==================== Skia 资源管理工具函数 ====================

/**
 * 安全地使用 TextLine，自动释放原生内存
 * @param text 文本内容
 * @param font 字体
 * @param block 使用 TextLine 的操作
 * @return block 的返回值
 */
inline fun <R> useTextLine(text: String, font: Font, block: (TextLine) -> R): R {
    val textLine = TextLine.make(text, font)
    return try {
        block(textLine)
    } finally {
        textLine.close()
    }
}

/**
 * 安全地创建并使用 Paragraph，自动释放原生内存
 * @param style 段落样式
 * @param fonts 字体集合
 * @param width 布局宽度
 * @param buildBlock 构建段落的操作
 * @param useBlock 使用段落的操作
 * @return useBlock 的返回值
 */
inline fun <R> useParagraph(
    style: ParagraphStyle,
    fonts: FontCollection,
    width: Float,
    buildBlock: (ParagraphBuilder) -> Unit,
    useBlock: (Paragraph) -> R
): R {
    val builder = ParagraphBuilder(style, fonts)
    buildBlock(builder)
    val paragraph = builder.build().layout(width)
    return try {
        useBlock(paragraph)
    } finally {
        paragraph.close()
    }
}

/**
 * 安全地创建 Paragraph 并返回，调用方负责关闭
 * 用于需要保留 Paragraph 引用的场景
 */
inline fun buildParagraph(
    style: ParagraphStyle,
    fonts: FontCollection,
    width: Float,
    buildBlock: (ParagraphBuilder) -> Unit
): Paragraph {
    val builder = ParagraphBuilder(style, fonts)
    buildBlock(builder)
    return builder.build().layout(width)
}

/**
 * 安全地使用多个 Paragraph，自动释放所有原生内存
 */
inline fun <R> useParagraphs(paragraphs: List<Paragraph>, block: () -> R): R {
    return try {
        block()
    } finally {
        paragraphs.forEach { runCatching { it.close() } }
    }
}

// ==================== Surface/Image 创建函数 ====================

/**
 * 使用 DrawingSession 创建图片（推荐）
 * 自动追踪和释放 Skia 资源，防止内存泄漏
 * @param width 图片宽度
 * @param height 图片高度
 * @param block 绑定操作，在 DrawingSession 上下文中执行
 * @return 生成的 Image
 */
suspend inline fun createImageWithSession(
    width: Int,
    height: Int,
    crossinline block: DrawingSession.(Canvas) -> Unit
): Image {
    return SkiaManager.executeDrawing {
        val surface = createSurface(width, height)
        block(surface.canvas)
        surface.makeImageSnapshot()
    }
}

/**
 * 安全地使用 Surface 创建图片快照（指定区域），确保 Surface 被正确关闭释放原生内存
 * @param width 图片宽度
 * @param height 图片高度
 * @param area 截取区域
 * @param block 绑定操作
 * @return 生成的 Image
 */
inline fun createImageWithArea(width: Int, height: Int, area: IRect, block: (Canvas) -> Unit): Image {
    val surface = Surface.makeRasterN32Premul(width, height)
    return try {
        block(surface.canvas)
        surface.makeImageSnapshot(area) ?: surface.makeImageSnapshot()
    } finally {
        surface.close()
    }
}

/**
 * Emoji正则(可匹配组合emoji)
 */
const val emojiCharacter =
    "(?:[\\uD83C\\uDF00-\\uD83D\\uDDFF]|[\\uD83E\\uDD00-\\uD83E\\uDDFF]|[\\uD83D\\uDE00-\\uD83D\\uDE4F]|[\\uD83D\\uDE80-\\uD83D\\uDEFF]|[\\u2600-\\u26FF]\\uFE0F?|[\\u2700-\\u27BF]\\uFE0F?|\\u24C2\\uFE0F?|[\\uD83C\\uDDE6-\\uD83C\\uDDFF]{1,2}|[\\uD83C\\uDD70\\uD83C\\uDD71\\uD83C\\uDD7E\\uD83C\\uDD7F\\uD83C\\uDD8E\\uD83C\\uDD91-\\uD83C\\uDD9A]\\uFE0F?|[\\u0023\\u002A\\u0030-\\u0039]\\uFE0F?\\u20E3|[\\u2194-\\u2199\\u21A9-\\u21AA]\\uFE0F?|[\\u2B05-\\u2B07\\u2B1B\\u2B1C\\u2B50\\u2B55]\\uFE0F?|[\\u2934\\u2935]\\uFE0F?|[\\u3030\\u303D]\\uFE0F?|[\\u3297\\u3299]\\uFE0F?|[\\uD83C\\uDE01\\uD83C\\uDE02\\uD83C\\uDE1A\\uD83C\\uDE2F\\uD83C\\uDE32-\\uD83C\\uDE3A\\uD83C\\uDE50\\uD83C\\uDE51]\\uFE0F?|[\\u203C\\u2049]\\uFE0F?|[\\u25AA\\u25AB\\u25B6\\u25C0\\u25FB-\\u25FE]\\uFE0F?|[\\u00A9\\u00AE]\\uFE0F?|[\\u2122\\u2139]\\uFE0F?|\\uD83C\\uDC04\\uFE0F?|\\uD83C\\uDCCF\\uFE0F?|[\\u231A\\u231B\\u2328\\u23CF\\u23E9-\\u23F3\\u23F8-\\u23FA]\\uFE0F?)(?:[\\uD83C\\uDFFB-\\uD83C\\uDFFF]|[\\uD83E\\uDDB0-\\uD83E\\uDDB3])?"
val emojiRegex = "${emojiCharacter}(?:\\u200D${emojiCharacter})*".toRegex()

/**
 * 图片加载失败时的占位图字节数据（缓存）
 * 使用字节数据而非 Image 对象，避免全局 Image 泄漏
 */
private val imageMissBytes: ByteArray by lazy {
    try {
        loadResourceBytes("image/IMAGE_MISS.png")
    } catch (e: Exception) {
        logger.error("无法加载 IMAGE_MISS.png: ${e.message}")
        // 返回空数组，后续会创建简单的粉色占位图
        ByteArray(0)
    }
}

/**
 * 获取图片加载失败时的占位图（使用 DrawingSession 追踪）
 * 推荐使用此版本，自动追踪和释放 Skia 资源
 */
fun getImageMiss(session: DrawingSession): Image {
    return if (imageMissBytes.isNotEmpty()) {
        with(session) {
            Image.makeFromEncoded(imageMissBytes).track()
        }
    } else {
        // 创建一个简单的粉色占位图
        val surface = session.createSurface(400, 300)
        surface.canvas.clear(Color.makeRGB(255, 192, 203))
        with(session) {
            surface.makeImageSnapshot().track()
        }
    }
}

enum class Position {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT
}

/**
 * 绘制矩形阴影
 */
fun Canvas.drawRectShadowAntiAlias(r: Rect, dx: Float, dy: Float, blur: Float, spread: Float, color: Int): Canvas {
    val insides = r.inflate(-1f)
    if (!insides.isEmpty) {
        save()
        if (insides is RRect) clipRRect(insides, ClipMode.DIFFERENCE, true)
        else clipRect(insides, ClipMode.DIFFERENCE, true)
        drawRectShadowNoclip(r, dx, dy, blur, spread, color)
        restore()
    } else drawRectShadowNoclip(r, dx, dy, blur, spread, color)
    return this
}

fun Canvas.drawRectShadowAntiAlias(r: Rect, shadow: Theme.Shadow): Canvas =
    drawRectShadowAntiAlias(r, shadow.offsetX, shadow.offsetY, shadow.blur, shadow.spread, shadow.shadowColor)


/**
 * 绘制圆角图片
 */
fun Canvas.drawImageRRect(image: Image, srcRect: Rect, rRect: RRect, paint: Paint? = null) {
    save()
    clipRRect(rRect, true)
    drawImageRect(image, srcRect, rRect, FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST), paint, false)
    restore()
}

fun Canvas.drawImageRRect(image: Image, rRect: RRect, paint: Paint? = null) =
    drawImageRRect(image, Rect(0f, 0f, image.width.toFloat(), image.height.toFloat()), rRect, paint)

fun loadSVG(path: String): SVGDOM? {
    return try {
        // 使用 classLoader 加载资源，确保从 classpath 根目录开始查找
        val resourcePath = if (path.startsWith("/")) path.substring(1) else path
        // ✅ 使用 use 确保资源正确关闭
        val resourceStream = top.bilibili.core.BiliBiliBot::class.java.classLoader.getResourceAsStream(resourcePath)
        if (resourceStream != null) {
            resourceStream.use {
                val data = Data.makeFromBytes(it.readBytes())
                try {
                    SVGDOM(data)
                } finally {
                    data.close()
                }
            }
        } else {
            logger.warn("SVG 资源未找到: $path")
            null
        }
    } catch (e: Exception) {
        logger.warn("加载 SVG 失败 ($path): ${e.message}")
        null
    }
}

/**
 * 渲染svg图片（使用 DrawingSession 追踪资源）
 * 推荐使用此版本，自动追踪和释放 Skia 资源
 */
fun SVGDOM.makeImage(session: DrawingSession, width: Float, height: Float): Image {
    setContainerSize(width, height)
    val surface = session.createSurface(width.toInt(), height.toInt())
    render(surface.canvas)
    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

fun Surface.saveImage(path: String) {
    val image = makeImageSnapshot()
    val data = image.encodeToData()!!
    try {
        File(path).writeBytes(data.bytes)
    } finally {
        data.close()
        image.close()
    }
}
//fun Surface.saveImage(path: java.nio.file.Path) = path.writeBytes(makeImageSnapshot().encodeToData()!!.bytes)

fun Canvas.drawScaleWidthImage(image: Image, width: Float, x: Float, y: Float, paint: Paint = Paint()) {
    val src = Rect.makeXYWH(0f, 0f, image.width.toFloat(), image.height.toFloat())
    val dst = Rect.makeXYWH(x, y, width, width * image.height / image.width)
    drawImageRect(image, src, dst, FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST), paint, false)
}

fun Canvas.drawScaleWidthImageOutline(
    image: Image,
    width: Float,
    x: Float,
    y: Float,
    isForward: Boolean = false,
    paint: Paint = Paint()
) {
    drawScaleWidthImage(image, width, x, y, paint)
    val dst = Rect.makeXYWH(x, y, width, width * image.height / image.width).toRRect()
    drawRRect(dst, Paint().apply {
        color = if (isForward) Color.BLUE else Color.GREEN
        mode = PaintMode.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    })
}

fun Rect.toRRect() =
    RRect.makeLTRB(left, top, right, bottom, 0f)

fun Rect.toRRect(radius: Float) =
    RRect.makeLTRB(left, top, right, bottom, radius)

fun RRect.offsetR(dx: Float, dy: Float): RRect {
    return RRect.makeComplexLTRB(left + dx, top + dy, right + dx, bottom + dy, radii)
}

fun Canvas.drawImageClip(
    session: DrawingSession,
    image: Image,
    dstRect: RRect,
    paint: Paint? = null
) {
    // 验证目标矩形的尺寸是否有效
    if (dstRect.width <= 0 || dstRect.height <= 0 || dstRect.width.isNaN() || dstRect.height.isNaN()) {
        logger.warn("目标矩形尺寸无效: width=${dstRect.width}, height=${dstRect.height}")
        return
    }

    // 如果图片尺寸无效，使用占位图
    val actualImage = if (image.width <= 0 || image.height <= 0) {
        logger.warn("图片尺寸无效: width=${image.width}, height=${image.height}，使用占位图替代")
        getImageMiss(session)
    } else {
        image
    }

    val ratio = actualImage.width.toFloat() / actualImage.height.toFloat()

    val srcRect = if (dstRect.width / ratio < dstRect.height) {
        val imgW = dstRect.width * actualImage.height / dstRect.height
        val offsetX = (actualImage.width - imgW) / 2f
        Rect.makeXYWH(offsetX, 0f, imgW, actualImage.height.toFloat())
    } else {
        val imgH = dstRect.height * actualImage.width / dstRect.width
        Rect.makeXYWH(0f, 0f, actualImage.width.toFloat(), imgH)
    }

    drawImageRRect(actualImage, srcRect, dstRect, paint)
}


fun Color.makeRGB(hex: String): Int {
    require(hex.startsWith("#")) { "Hex format error: $hex" }
    require(hex.length == 7 || hex.length == 9) { "Hex length error: $hex" }
    return when (hex.length) {
        7 -> {
            makeRGB(
                Integer.valueOf(hex.substring(1, 3), 16),
                Integer.valueOf(hex.substring(3, 5), 16),
                Integer.valueOf(hex.substring(5), 16)
            )
        }

        9 -> {
            makeARGB(
                Integer.valueOf(hex.substring(1, 3), 16),
                Integer.valueOf(hex.substring(3, 5), 16),
                Integer.valueOf(hex.substring(5, 7), 16),
                Integer.valueOf(hex.substring(7), 16)
            )
        }

        else -> {
            WHITE
        }
    }
}

fun Color.getRGB(color: Int) = intArrayOf(getR(color), getG(color), getB(color))

fun rgb2hsb(rgbR: Int, rgbG: Int, rgbB: Int): FloatArray {

    val rgb = intArrayOf(rgbR, rgbG, rgbB)
    Arrays.sort(rgb)
    val max = rgb[2]
    val min = rgb[0]
    val hsbB = max / 255.0f
    val hsbS: Float = if (max == 0) 0f else (max - min) / max.toFloat()
    var hsbH = 0f
    if (max == rgbR && rgbG >= rgbB) {
        hsbH = (rgbG - rgbB) * 60f / (max - min) + 0
    } else if (max == rgbR && rgbG < rgbB) {
        hsbH = (rgbG - rgbB) * 60f / (max - min) + 360
    } else if (max == rgbG) {
        hsbH = (rgbB - rgbR) * 60f / (max - min) + 120
    } else if (max == rgbB) {
        hsbH = (rgbR - rgbG) * 60f / (max - min) + 240
    }
    return floatArrayOf(hsbH, hsbS, hsbB)
}

fun hsb2rgb(h: Float, s: Float, v: Float): IntArray {
    var r = 0f
    var g = 0f
    var b = 0f
    val i = (h / 60 % 6).toInt()
    val f = h / 60 - i
    val p = v * (1 - s)
    val q = v * (1 - f * s)
    val t = v * (1 - (1 - f) * s)
    when (i) {
        0 -> {
            r = v
            g = t
            b = p
        }

        1 -> {
            r = q
            g = v
            b = p
        }

        2 -> {
            r = p
            g = v
            b = t
        }

        3 -> {
            r = p
            g = q
            b = v
        }

        4 -> {
            r = t
            g = p
            b = v
        }

        5 -> {
            r = v
            g = p
            b = q
        }

        else -> {}
    }
    return intArrayOf((r * 255.0).toInt(), (g * 255.0).toInt(), (b * 255.0).toInt())
}

fun generateLinearGradient(colors: List<Int>): IntArray {
    val colorGenerator = currentRenderSnapshot().colorGenerator
    return if (colors.size == 1) {
        val hsb = rgb2hsb(Color.getR(colors[0]), Color.getG(colors[0]), Color.getB(colors[0]))
        if (colorGenerator.lockSB) {
            hsb[1] = colorGenerator.saturation
            hsb[2] = colorGenerator.brightness
        }
        val linearLayerCount = 3
        val linearLayerStep = colorGenerator.hueStep
        val llc = if (linearLayerCount % 2 == 0) linearLayerCount + 1 else linearLayerCount
        val ia = IntArray(llc)
        hsb[0] = (hsb[0] + linearLayerCount / 2 * linearLayerStep) % 360
        repeat(llc) {
            val c = hsb2rgb(hsb[0], hsb[1], hsb[2])
            ia[it] = Color.makeRGB(c[0], c[1], c[2])
            hsb[0] = if (hsb[0] - linearLayerStep < 0) hsb[0] + 360 - linearLayerStep else hsb[0] - linearLayerStep
        }
        ia
    } else {
        val llc = colors.size
        val ia = IntArray(llc)
        repeat(llc) {
            val hsb = rgb2hsb(Color.getR(colors[it]), Color.getG(colors[it]), Color.getB(colors[it]))
            if (colorGenerator.lockSB) {
                hsb[1] = colorGenerator.saturation
                hsb[2] = colorGenerator.brightness
            }
            val c = hsb2rgb(hsb[0], hsb[1], hsb[2])
            ia[it] = Color.makeRGB(c[0], c[1], c[2])
        }
        ia
    }
}
