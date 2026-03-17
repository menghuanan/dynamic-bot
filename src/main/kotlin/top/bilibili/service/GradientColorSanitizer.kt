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

fun normalizeGradientColorInput(color: String): String? {
    val segments = splitGradientSegments(color)
    if (segments.isEmpty() || segments.any { it.isEmpty() }) return null
    if (segments.size > MAX_GRADIENT_COLOR_STOPS) return null
    if (segments.any { !gradientHexColorRegex.matches(it) }) return null
    return segments.joinToString(";")
}

fun normalizeGradientColorForCache(color: String): String {
    return sanitizeGradientColor(color) ?: ""
}

fun sanitizeGradientColor(color: String): String? {
    val segments = splitGradientSegments(color)
        .filter { it.isNotEmpty() }
        .filter { gradientHexColorRegex.matches(it) }
        .take(MAX_GRADIENT_COLOR_STOPS)
    return segments.takeIf { it.isNotEmpty() }?.joinToString(";")
}

fun parseGradientColors(color: String, fallback: String = defaultGradientColor()): List<Int> {
    val normalized = sanitizeGradientColor(color)
        ?: sanitizeGradientColor(fallback)
        ?: "#d3edfa"
    return normalized.split(';').map { Color.makeRGB(it) }
}

private fun defaultGradientColor(): String {
    return runCatching { BiliConfigManager.config.imageConfig.defaultColor }
        .getOrElse { BiliConfig().imageConfig.defaultColor }
}
