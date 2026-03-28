package top.bilibili.service

import org.jetbrains.skia.Color
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.draw.makeRGB

const val MAX_GRADIENT_COLOR_STOPS = 4

private val gradientHexColorRegex = Regex("^#[0-9A-Fa-f]{6}$")

private fun splitGradientSegments(color: String): List<String> {
    return color.split(";", "；").map { it.trim() }
}

/**
 * 严格校验渐变色输入格式，确保写入配置前就拦截非法颜色段。
 */
fun normalizeGradientColorInput(color: String): String? {
    val segments = splitGradientSegments(color)
    if (segments.isEmpty() || segments.any { it.isEmpty() }) return null
    if (segments.size > MAX_GRADIENT_COLOR_STOPS) return null
    if (segments.any { !gradientHexColorRegex.matches(it) }) return null
    return segments.joinToString(";")
}

/**
 * 为缓存键提供稳定颜色串，优先复用完整归一化结果再退回宽松清洗。
 */
fun normalizeGradientColorForCache(color: String): String {
    return normalizeSubjectScopedGradientColor(color, NormalizationContext.USER_COMMAND)?.normalizedColor
        ?: sanitizeGradientColor(color)
        ?: ""
}

/**
 * 以宽松模式清洗颜色输入，尽量从脏数据中提取可继续使用的有效色值。
 */
fun sanitizeGradientColor(color: String): String? {
    val segments = splitGradientSegments(color)
        .filter { it.isNotEmpty() }
        .filter { gradientHexColorRegex.matches(it) }
        .take(MAX_GRADIENT_COLOR_STOPS)
    return segments.takeIf { it.isNotEmpty() }?.joinToString(";") { it.lowercase() }
}

/**
 * 解析渐变色为绘图颜色列表，并在输入无效时退回默认主题色。
 */
fun parseGradientColors(color: String, fallback: String = defaultGradientColor()): List<Int> {
    val normalized = sanitizeGradientColor(color)
        ?: sanitizeGradientColor(fallback)
        ?: "#d3edfa"
    return normalized.split(';').map { Color.makeRGB(it) }
}

private fun defaultGradientColor(): String {
    return defaultGradientColorValue()
}

internal fun defaultGradientColorValue(): String {
    return runCatching { BiliConfigManager.config.imageConfig.defaultColor }
        .getOrElse { BiliConfig().imageConfig.defaultColor }
}
