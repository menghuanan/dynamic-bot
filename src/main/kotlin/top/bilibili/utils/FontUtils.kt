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
            fontProvider.registerTypeface(typeface)
        }

        val aliasKey = alias?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
        if (aliasKey != null && registeredAliasKeys.add(aliasKey)) {
            fontProvider.registerTypeface(typeface, alias)
        }
    }


    fun matchFamily(familyName: String): FontStyleSet {
        val providerFamily = fontProvider.matchFamily(familyName)
        if (providerFamily.count() != 0) {
            return providerFamily
        }
        providerFamily.close()
        return fontMgr.matchFamily(familyName)
    }

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

    fun loadTypeface(path: String, alias: String? = null, index: Int = 0): Typeface {
        val face = fontMgr.makeFromFile(path, index) ?: throw IllegalArgumentException("无法加载字体: $path")
        if (defaultFont == null) defaultFont = face
        registerTypeface(face, alias)
        logger.info("加载字体 ${face.familyName} 成功")
        return face
    }

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
