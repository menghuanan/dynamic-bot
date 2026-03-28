package top.bilibili.utils

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

object FontUtils {

    private val fontMgr = FontMgr.default
    private val fontProvider = TypefaceFontProvider()
    val fonts = FontCollection().setDynamicFontManager(fontProvider).setDefaultFontManager(fontMgr)
    private val registeredTypefaceKeys = ConcurrentHashMap.newKeySet<String>()
    private val registeredAliasKeys = ConcurrentHashMap.newKeySet<String>()

    var defaultFont: Typeface? = null

    /**
     * 向全局字体集合注册字体，并按字体特征与别名去重。
     */
    private fun registerTypeface(typeface: Typeface?, alias: String? = null) {
        if (typeface == null) return

        val style = typeface.fontStyle
        val baseKey = buildString {
            append(typeface.familyName.lowercase(Locale.ROOT))
            append(':')
            append(style.weight)
            append(':')
            append(style.width)
            append(':')
            append(style.slant.ordinal)
        }
        if (registeredTypefaceKeys.add(baseKey)) {
            // 这里先按完整样式键去重，是为了避免同一字体被重复注册后污染匹配结果。
            fontProvider.registerTypeface(typeface)
        }

        val aliasKey = alias?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        if (aliasKey != null && registeredAliasKeys.add(aliasKey)) {
            // 别名单独去重，是为了允许同一字体本体与多个显式别名分开控制注册策略。
            fontProvider.registerTypeface(typeface, alias)
        }
    }


    /**
     * 按字体族名称匹配字体集合，优先查找运行时动态注册的字体。
     */
    fun matchFamily(familyName: String): FontStyleSet {
        val providerFamily = fontProvider.matchFamily(familyName)
        if (providerFamily.count() != 0) {
            return providerFamily
        }
        providerFamily.close()
        return fontMgr.matchFamily(familyName)
    }

    /**
     * 按字体族名称与样式匹配具体字体，优先使用运行时注册的字体。
     */
    fun matchFamilyStyle(familyName: String, style: FontStyle): Typeface? {
        fontProvider.matchFamily(familyName).use { providerFamily ->
            if (providerFamily.count() != 0) {
                return providerFamily.matchStyle(style)
            }
        }
        return fontMgr.matchFamily(familyName).use { systemFamily ->
            systemFamily.matchStyle(style)
        }
    }

    /**
     * 从文件系统加载字体并注册到全局字体集合。
     */
    fun loadTypeface(path: String, alias: String? = null, index: Int = 0): Typeface {
        val face = fontMgr.makeFromFile(path, index) ?: throw IllegalArgumentException("无法加载字体: $path")
        if (defaultFont == null) defaultFont = face
        registerTypeface(face, alias)
        logger.info("加载字体 ${face.familyName} 成功")
        return face
    }

    /**
     * 从类路径资源加载字体并注册到全局字体集合。
     */
    fun loadTypefaceFromResource(resourcePath: String, alias: String? = null, index: Int = 0): Typeface? {
        return try {
            // 使用 classLoader 加载资源，确保从 classpath 根目录开始查找
            val path = if (resourcePath.startsWith("/")) resourcePath.substring(1) else resourcePath
            // ✅ 使用 use 确保资源正确关闭
            val inputStream = FontUtils::class.java.classLoader.getResourceAsStream(path)
            if (inputStream != null) {
                val bytes = inputStream.use { it.readBytes() }
                val data = Data.makeFromBytes(bytes)
                try {
                    val face = fontMgr.makeFromData(data, index) ?: throw IllegalArgumentException("无法从数据加载字体")
                    if (defaultFont == null) defaultFont = face
                    registerTypeface(face, alias)
                    logger.info("从 resources 加载字体 ${alias ?: face.familyName} 成功")
                    face
                } finally {
                    data.close()
                }
            } else {
                logger.warn("资源文件不存在: $resourcePath")
                null
            }
        } catch (e: Exception) {
            logger.error("从 resources 加载字体失败: $resourcePath", e)
            null
        }
    }

    /**
     * 从内存数据加载字体并注册到全局字体集合。
     */
    fun loadTypeface(data: Data, index: Int = 0): Typeface {
        return try {
            val face = fontMgr.makeFromData(data, index) ?: throw IllegalArgumentException("无法从数据加载字体")
            if (defaultFont == null) defaultFont = face
            registerTypeface(face)
            face
        } finally {
            data.close()
        }
    }

    /**
     * 重置全局 ParagraphCache，避免长时间运行时排版缓存持续积累。
     */
    fun resetParagraphCache() {
        runCatching {
            val cachedCount = runCatching { fonts.paragraphCache.count }.getOrDefault(-1)
            fonts.paragraphCache.reset()
            if (cachedCount >= 0) {
                logger.debug("已重置全局 ParagraphCache，重置前条目数: $cachedCount")
            } else {
                logger.debug("已重置全局 ParagraphCache")
            }
        }.onFailure { error ->
            logger.warn("重置全局 ParagraphCache 失败: ${error.message}")
        }
    }

}
