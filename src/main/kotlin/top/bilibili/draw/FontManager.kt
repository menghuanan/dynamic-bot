package top.bilibili.draw

import org.jetbrains.skia.Data
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Typeface
import top.bilibili.utils.FontUtils
import top.bilibili.utils.loadResourceBytes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Font manager for draw rendering.
 * Caches fonts by render snapshot instead of reading mutable global config in draw hot paths.
 */
object FontManager : AutoCloseable {
    private val logger by lazy { top.bilibili.core.BiliBiliBot.logger }
    private val closed = AtomicBoolean(false)

    private val typefaceCache = ConcurrentHashMap<String, Typeface>()
    private val fontCache = ConcurrentHashMap<String, Font>()
    private val emojiTypefaceCache = ConcurrentHashMap<String, Typeface?>()
    private val emojiFontCache = ConcurrentHashMap<String, Font>()
    private val fansCardFontCache = ConcurrentHashMap<String, Font?>()

    val mainTypeface: Typeface
        get() = mainTypefaceFor(currentRenderSnapshot())

    val font: Font
        get() = fontFor(currentRenderSnapshot())

    val emojiTypeface: Typeface?
        get() = emojiTypefaceFor(currentRenderSnapshot())

    val emojiFont: Font
        get() = emojiFontFor(currentRenderSnapshot())

    val fansCardFont: Font?
        get() = fansCardFontFor(currentRenderSnapshot())

    fun mainTypefaceFor(snapshot: RenderSnapshot): Typeface {
        ensureOpen()
        val requestedFamily = snapshot.fontFamily.trim()
        val cacheKey = if (requestedFamily.isBlank()) DEFAULT_FONT_KEY else requestedFamily
        return typefaceCache.computeIfAbsent(cacheKey) {
            loadMainTypeface(requestedFamily)
        }
    }

    fun fontFor(snapshot: RenderSnapshot): Font {
        ensureOpen()
        val typeface = mainTypefaceFor(snapshot)
        val cacheKey = "${typeface.familyName}:${snapshot.quality.contentFontSize}"
        return fontCache.computeIfAbsent(cacheKey) {
            Font(typeface, snapshot.quality.contentFontSize)
        }
    }

    fun emojiTypefaceFor(snapshot: RenderSnapshot): Typeface? {
        ensureOpen()
        val typeface = mainTypefaceFor(snapshot)
        return emojiTypefaceCache.computeIfAbsent(typeface.familyName) {
            loadEmojiTypeface()
        }
    }

    fun emojiFontFor(snapshot: RenderSnapshot): Font {
        ensureOpen()
        val typeface = emojiTypefaceFor(snapshot) ?: mainTypefaceFor(snapshot)
        val cacheKey = "${typeface.familyName}:${snapshot.quality.contentFontSize}:emoji"
        return emojiFontCache.computeIfAbsent(cacheKey) {
            Font(typeface, snapshot.quality.contentFontSize)
        }
    }

    fun fansCardFontFor(snapshot: RenderSnapshot): Font? {
        ensureOpen()
        val cacheKey = snapshot.quality.subTitleFontSize.toString()
        return fansCardFontCache.computeIfAbsent(cacheKey) {
            loadFansCardFont(snapshot.quality.subTitleFontSize)
        }
    }

    private fun ensureOpen() {
        if (closed.get()) throw IllegalStateException("FontManager has been closed")
    }

    private fun loadMainTypeface(fontFamily: String): Typeface {
        return try {
            if (fontFamily.isBlank()) {
                logger.warn("No configured draw font, trying bundled or system defaults")
                FontUtils.defaultFont ?: throw Exception("No default font found")
            } else {
                FontUtils.matchFamily(fontFamily).matchStyle(FontStyle.NORMAL)!!
            }
        } catch (e: Exception) {
            logger.warn("Failed to load draw font '$fontFamily', falling back to defaults")
            loadSysDefaultFont()
        }
    }

    private fun loadEmojiTypeface(): Typeface? {
        return try {
            FontUtils.matchFamily("Noto Color Emoji")?.matchStyle(FontStyle.NORMAL)
        } catch (e: Exception) {
            logger.warn("Unable to load emoji typeface: ${e.message}")
            null
        }
    }

    private fun loadFansCardFont(fontSize: Float): Font? {
        return try {
            Font(
                FontUtils.loadTypeface(Data.makeFromBytes(loadResourceBytes("/font/FansCard.ttf"))),
                fontSize,
            )
        } catch (e: Exception) {
            logger.warn("Unable to load FansCard.ttf: ${e.message}")
            null
        }
    }

    private fun loadSysDefaultFont(): Typeface {
        val defaultList = listOf(
            "Source Han Sans SC",
            "Source Han Sans",
            "Microsoft YaHei",
            "SimHei",
            "sans-serif",
        )
        defaultList.forEach {
            try {
                return FontUtils.matchFamily(it).matchStyle(FontStyle.NORMAL)!!
            } catch (_: Exception) {
            }
        }
        throw Exception("Unable to load a default font")
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            fontCache.values.forEach { runCatching { it.close() } }
            emojiFontCache.values.forEach { runCatching { it.close() } }
            fansCardFontCache.values.forEach { font -> runCatching { font?.close() } }

            fontCache.clear()
            emojiFontCache.clear()
            fansCardFontCache.clear()
            typefaceCache.clear()
            emojiTypefaceCache.clear()
        }
    }

    private const val DEFAULT_FONT_KEY = "__default__"
}