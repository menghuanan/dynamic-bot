package top.bilibili.service

import org.jetbrains.skia.Color
import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.draw.generateLinearGradient
import top.bilibili.draw.hsb2rgb
import top.bilibili.draw.rgb2hsb
import kotlin.math.abs
import kotlin.math.pow

private const val SUBJECT_SEGMENT_INTERIOR_STOPS = 16
private const val SUBJECT_RENDER_L_MIN = 0.0
private const val SUBJECT_RENDER_L_MAX = 1.0
private const val SINGLE_RENDER_DARK_OFFSET = -0.10
private const val SINGLE_RENDER_LIGHT_OFFSET = 0.04
private const val SINGLE_RENDER_DARK_CHROMA_SCALE = 1.08
private const val SINGLE_RENDER_LIGHT_CHROMA_SCALE = 0.82
private const val NEUTRAL_RENDER_CHROMA = 0.0025
private const val INTERPOLATION_T_GAMMA = 1.08
private const val INTERPOLATION_MIDPOINT_LIGHTNESS_DIP = 0.03

/**
 * 标记主题色最终命中的来源，便于渲染与诊断区分绑定值和默认回退。
 */
enum class ResolvedColorSourceType {
    SUBJECT_BOUND_NORMALIZED,
    DEFAULT_COLOR_FALLBACK,
}

/**
 * 描述主题色为何退回默认值，便于日志和命令提示保留上下文。
 */
enum class ColorFallbackReason {
    NO_BINDING,
    BINDING_PARSE_FAILED,
    NORMALIZATION_FAILED,
}

/**
 * 封装解析后的主题色来源信息，让渲染链路能感知回退原因。
 */
data class ResolvedColorSource(
    val sourceType: ResolvedColorSourceType,
    val color: String,
    val fallbackReason: ColorFallbackReason? = null,
)

internal enum class InterpolationHintSource {
    LEFT_NEIGHBOR,
    RIGHT_NEIGHBOR,
    INTERPOLATED,
}

internal data class InterpolationHint(
    val stopIndex: Int,
    val resolvedHue: Double,
    val source: InterpolationHintSource,
)

/**
 * 聚合主题色、背景渐变和来源信息，避免绘图层重复解析颜色来源。
 */
data class ResolvedGradientPalette(
    val themeColor: Int,
    val backgroundColors: IntArray,
    val source: ResolvedColorSource,
)

/**
 * 将会话主题色转换为可用于绘图背景的渐变色数组。
 */
fun generateSubjectScopedGradientColors(color: String): IntArray {
    val normalized = normalizeSubjectScopedGradientColor(color, NormalizationContext.USER_COMMAND)?.normalizedColor
        ?: throw IllegalArgumentException("invalid subject scoped gradient color: $color")
    return generateSubjectScopedGradientColorsFromNormalized(normalized)
}

/**
 * 按 UID 和会话解析完整渐变调色板，供绘图链路直接消费。
 */
fun resolveGradientPalette(uid: Long, subject: String?): ResolvedGradientPalette {
    return resolveGradientPalette(ColorBindingService.resolveColorSource(uid, subject))
}

/**
 * 基于已解析的颜色来源生成最终调色板，统一处理回退和异常兜底。
 */
fun resolveGradientPalette(source: ResolvedColorSource): ResolvedGradientPalette {
    return when (source.sourceType) {
        ResolvedColorSourceType.DEFAULT_COLOR_FALLBACK -> legacyGradientPalette(source)
        ResolvedColorSourceType.SUBJECT_BOUND_NORMALIZED -> {
            runCatching {
                val themeColor = parseGradientColors(source.color).first()
                ResolvedGradientPalette(
                    themeColor = themeColor,
                    backgroundColors = generateSubjectScopedGradientColorsFromNormalized(source.color),
                    source = source,
                )
            }.getOrElse {
                BiliBiliBot.logger.warn("Failed to generate subject-scoped gradient for color=${source.color}", it)
                legacyGradientPalette(
                    ResolvedColorSource(
                        sourceType = ResolvedColorSourceType.DEFAULT_COLOR_FALLBACK,
                        color = defaultGradientColorValue(),
                        fallbackReason = ColorFallbackReason.NORMALIZATION_FAILED,
                    )
                )
            }
        }
    }
}

private fun legacyGradientPalette(source: ResolvedColorSource): ResolvedGradientPalette {
    val colors = parseGradientColors(source.color)
    return ResolvedGradientPalette(
        themeColor = colors.first(),
        backgroundColors = generateLinearGradient(colors),
        source = source,
    )
}

private fun generateSubjectScopedGradientColorsFromNormalized(color: String): IntArray {
    val normalizedStops = normalizeGradientColorInput(color)?.split(';')
        ?: error("normalized subject scoped color is invalid: $color")
    val stops = normalizedStops.map(::srgbHexToOklch)
    val renderStops = if (stops.size == 1) expandSingleColorRenderStops(stops.first()) else buildMultiColorRenderStops(stops)
    return interpolateSubjectScopedGradient(renderStops)
}

private fun expandSingleColorRenderStops(main: OklchColor): List<OklchColor> {
    if (main.c >= OKLCH_NEUTRAL_THRESHOLD) {
        return buildLegacyHueFanSingleRenderStops(main)
    }

    val center = legacyLockSbTunedStop(main)
    val dark = center.copyColor().apply {
        l = (l + SINGLE_RENDER_DARK_OFFSET).coerceIn(SUBJECT_RENDER_L_MIN, SUBJECT_RENDER_L_MAX)
        c *= SINGLE_RENDER_DARK_CHROMA_SCALE
    }
    val light = center.copyColor().apply {
        l = (l + SINGLE_RENDER_LIGHT_OFFSET).coerceIn(SUBJECT_RENDER_L_MIN, SUBJECT_RENDER_L_MAX)
        c *= SINGLE_RENDER_LIGHT_CHROMA_SCALE
    }

    if (abs(dark.l - (center.l + SINGLE_RENDER_DARK_OFFSET)) > L_EPSILON ||
        abs(light.l - (center.l + SINGLE_RENDER_LIGHT_OFFSET)) > L_EPSILON
    ) {
        BiliBiliBot.logger.debug("Single-color render endpoints were clamped for normalizedColor=${encodeStop(center)}")
    }

    return listOf(dark, center, light)
}

private fun buildLegacyHueFanSingleRenderStops(main: OklchColor): List<OklchColor> {
    val rgb = oklchToRgb(main)
    val hsb = rgb2hsb(rgb[0], rgb[1], rgb[2])
    val generator = BiliConfigManager.config.imageConfig.colorGenerator
    if (generator.lockSB) {
        hsb[1] = generator.saturation
        hsb[2] = generator.brightness
    }

    val hueStep = generator.hueStep.toFloat()
    var hue = (hsb[0] + hueStep) % 360f

    return buildList(3) {
        repeat(3) {
            val tuned = hsb2rgb(hue, hsb[1], hsb[2])
            add(srgbHexToOklch("#%02x%02x%02x".format(tuned[0], tuned[1], tuned[2])))
            hue = if (hue - hueStep < 0f) hue + 360f - hueStep else hue - hueStep
        }
    }
}

private fun buildMultiColorRenderStops(stops: List<OklchColor>): List<OklchColor> {
    val tunedStops = stops.map(::legacyLockSbTunedStop)
    val hints = buildInterpolationHints(stops).associateBy { it.stopIndex }
    return stops.mapIndexed { index, stop ->
        if (stop.c < OKLCH_NEUTRAL_THRESHOLD) {
            val hint = hints[index]
            OklchColor(
                l = stop.l,
                c = NEUTRAL_RENDER_CHROMA,
                h = wrapHue(hint?.resolvedHue ?: stop.h),
            )
        } else {
            tunedStops[index]
        }
    }
}

private fun buildInterpolationHints(stops: List<OklchColor>): List<InterpolationHint> {
    return stops.mapIndexedNotNull { index, stop ->
        if (stop.c >= OKLCH_NEUTRAL_THRESHOLD) {
            null
        } else {
            resolveNeutralInterpolationHint(index, stops)
        }
    }
}

private fun resolveNeutralInterpolationHint(index: Int, stops: List<OklchColor>): InterpolationHint {
    val current = stops[index]
    val leftIndex = (index - 1 downTo 0).firstOrNull { stops[it].c >= OKLCH_NEUTRAL_THRESHOLD }
    val rightIndex = (index + 1 until stops.size).firstOrNull { stops[it].c >= OKLCH_NEUTRAL_THRESHOLD }

    return when {
        leftIndex != null && rightIndex != null -> {
            val left = stops[leftIndex]
            val right = stops[rightIndex]
            val leftDistance = abs(current.l - left.l)
            val rightDistance = abs(right.l - current.l)
            val totalDistance = leftDistance + rightDistance
            val t = if (totalDistance > L_EPSILON) leftDistance / totalDistance else 0.5
            val resolvedHue = wrapHue(left.h + shortestHueDeltaDegrees(left.h, right.h) * t)
            InterpolationHint(index, resolvedHue, InterpolationHintSource.INTERPOLATED)
        }

        leftIndex != null -> InterpolationHint(index, stops[leftIndex].h, InterpolationHintSource.LEFT_NEIGHBOR)
        rightIndex != null -> InterpolationHint(index, stops[rightIndex].h, InterpolationHintSource.RIGHT_NEIGHBOR)
        else -> InterpolationHint(index, current.h, InterpolationHintSource.INTERPOLATED)
    }
}

private fun interpolateSubjectScopedGradient(stops: List<OklchColor>): IntArray {
    if (stops.isEmpty()) return IntArray(0)
    val controlPoints = stops.map { it.toOklab() }
    val colors = ArrayList<Int>(1 + (controlPoints.size - 1) * (SUBJECT_SEGMENT_INTERIOR_STOPS + 1))
    colors += oklabToSkiaColor(controlPoints.first())

    for (index in 0 until controlPoints.lastIndex) {
        val start = controlPoints[index]
        val end = controlPoints[index + 1]
        for (step in 1..SUBJECT_SEGMENT_INTERIOR_STOPS) {
            val linearT = step.toDouble() / (SUBJECT_SEGMENT_INTERIOR_STOPS + 1).toDouble()
            val t = remapInterpolationFactor(linearT)
            val lightness = applyInterpolationMidpointBias(
                start.l + (end.l - start.l) * t,
                linearT,
            )
            colors += oklabToSkiaColor(
                OklabColor(
                    l = lightness,
                    a = start.a + (end.a - start.a) * t,
                    b = start.b + (end.b - start.b) * t,
                )
            )
        }
        colors += oklabToSkiaColor(end)
    }

    return colors.toIntArray()
}

private fun legacyLockSbTunedStop(stop: OklchColor): OklchColor {
    if (stop.c < OKLCH_NEUTRAL_THRESHOLD) {
        return stop.copyColor().apply {
            h = wrapHue(h)
        }
    }

    val color = oklchToRgb(stop)
    val hsb = rgb2hsb(color[0], color[1], color[2])
    val generator = BiliConfigManager.config.imageConfig.colorGenerator
    if (generator.lockSB) {
        hsb[1] = generator.saturation
        hsb[2] = generator.brightness
    }
    val tuned = hsb2rgb(hsb[0], hsb[1], hsb[2])
    return srgbHexToOklch("#%02x%02x%02x".format(tuned[0], tuned[1], tuned[2]))
}

private fun remapInterpolationFactor(t: Double): Double {
    return t.pow(INTERPOLATION_T_GAMMA)
}

private fun applyInterpolationMidpointBias(lightness: Double, t: Double): Double {
    val bias = 1.0 - INTERPOLATION_MIDPOINT_LIGHTNESS_DIP * 4.0 * t * (1.0 - t)
    return (lightness * bias).coerceIn(0.0, 1.0)
}

private fun oklabToSkiaColor(color: OklabColor): Int {
    val linear = oklabToLinearRgb(color)
    return Color.makeRGB(
        linearToSrgb(linear[0]),
        linearToSrgb(linear[1]),
        linearToSrgb(linear[2]),
    )
}
