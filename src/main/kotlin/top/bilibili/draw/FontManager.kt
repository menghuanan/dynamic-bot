package top.bilibili.draw

import org.jetbrains.skia.Font
import org.jetbrains.skia.Typeface
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Data
import top.bilibili.BiliConfigManager
import top.bilibili.utils.FontUtils
import top.bilibili.utils.loadResourceBytes
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 字体管理器 - 管理所有全局字体对象的生命周期
 * 在应用关闭时统一释放，避免 native 内存泄漏
 */
object FontManager : AutoCloseable {
    private val logger by lazy { top.bilibili.core.BiliBiliBot.logger }
    private val closed = AtomicBoolean(false)

    // 延迟初始化的字体对象
    private var _mainTypeface: Typeface? = null
    private var _font: Font? = null
    private var _emojiTypeface: Typeface? = null
    private var _emojiFont: Font? = null
    private var _fansCardFont: Font? = null

    val mainTypeface: Typeface
        get() {
            if (closed.get()) throw IllegalStateException("FontManager has been closed")
            if (_mainTypeface == null) {
                synchronized(this) {
                    if (_mainTypeface == null) {
                        _mainTypeface = loadMainTypeface()
                    }
                }
            }
            return _mainTypeface!!
        }

    val font: Font
        get() {
            if (closed.get()) throw IllegalStateException("FontManager has been closed")
            if (_font == null) {
                synchronized(this) {
                    if (_font == null) {
                        _font = Font(mainTypeface, quality.contentFontSize)
                    }
                }
            }
            return _font!!
        }

    val emojiTypeface: Typeface?
        get() {
            if (closed.get()) throw IllegalStateException("FontManager has been closed")
            if (_emojiTypeface == null) {
                synchronized(this) {
                    if (_emojiTypeface == null) {
                        _emojiTypeface = loadEmojiTypeface()
                    }
                }
            }
            return _emojiTypeface
        }

    val emojiFont: Font
        get() {
            if (closed.get()) throw IllegalStateException("FontManager has been closed")
            if (_emojiFont == null) {
                synchronized(this) {
                    if (_emojiFont == null) {
                        // 如果 emoji typeface 为 null，使用 mainTypeface 作为后备
                        val typeface = emojiTypeface ?: mainTypeface
                        _emojiFont = Font(typeface, quality.contentFontSize)
                    }
                }
            }
            return _emojiFont!!
        }

    val fansCardFont: Font?
        get() {
            if (closed.get()) throw IllegalStateException("FontManager has been closed")
            if (_fansCardFont == null) {
                synchronized(this) {
                    if (_fansCardFont == null) {
                        _fansCardFont = loadFansCardFont()
                    }
                }
            }
            return _fansCardFont
        }

    /**
     * 加载主字体，优先使用配置项，其次回退到系统默认字体。
     */
    private fun loadMainTypeface(): Typeface {
        val imageConfig = BiliConfigManager.config.imageConfig
        val mainFont = imageConfig.font.split(";").first().split(".").first()
        return try {
            if (mainFont.isBlank()) {
                logger.warn("配置文件未配置字体, 尝试加载 font 目录下的字体")
                val f = FontUtils.defaultFont
                if (f == null) {
                    throw Exception("No default font found")
                } else {
                    logger.info("成功加载 ${f.familyName} 字体")
                    f
                }
            } else {
                FontUtils.matchFamilyStyle(mainFont, FontStyle.NORMAL)!!
            }
        } catch (e: Exception) {
            logger.warn("加载主字体 $mainFont 失败, 尝试加载默认字体")
            loadSysDefaultFont()
        }
    }

    /**
     * 加载 Emoji 字体，失败时返回空并走主字体回退。
     */
    private fun loadEmojiTypeface(): Typeface? {
        return try {
            FontUtils.matchFamilyStyle("Noto Color Emoji", FontStyle.NORMAL)
        } catch (e: Exception) {
            logger.warn("无法加载 Emoji 字体: ${e.message}")
            null
        }
    }

    /**
     * 加载粉丝卡专用字体。
     */
    private fun loadFansCardFont(): Font? {
        return try {
            Font(
                FontUtils.loadTypeface(Data.makeFromBytes(loadResourceBytes("/font/FansCard.ttf"))),
                quality.subTitleFontSize
            )
        } catch (e: Exception) {
            logger.warn("无法加载粉丝卡字体 FansCard.ttf: ${e.message}，将跳过卡片装饰")
            null
        }
    }

    /**
     * 加载系统默认字体链。
     */
    private fun loadSysDefaultFont(): Typeface {
        // 优先使用内嵌字体，然后尝试常见的系统字体
        val defaultList = listOf(
            "Source Han Sans SC",  // 内嵌字体：SourceHanSansSC-Regular.otf
            "Source Han Sans",
            "Microsoft YaHei",     // Windows 系统字体
            "SimHei",              // Windows 系统字体
            "sans-serif"           // 通用字体
        )
        defaultList.forEach {
            try {
                val f = FontUtils.matchFamilyStyle(it, FontStyle.NORMAL)!!
                logger.info("加载默认字体 $it 成功")
                return f
            } catch (e: Exception) {
                logger.debug("尝试加载默认字体 $it 失败: ${e.message}")
            }
        }
        throw Exception("无法加载默认字体, 请自行配置字体或准备字体文件")
    }

    /**
     * 关闭字体管理器并释放 Font 资源。
     */
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(this) {
                logger.info("FontManager 正在关闭，释放所有字体资源...")

                _font?.close()
                _font = null

                _emojiFont?.close()
                _emojiFont = null

                _fansCardFont?.close()
                _fansCardFont = null

                // Typeface 不需要手动关闭，由 JVM 管理
                _mainTypeface = null
                _emojiTypeface = null

                logger.info("FontManager 已关闭")
            }
        }
    }
}
