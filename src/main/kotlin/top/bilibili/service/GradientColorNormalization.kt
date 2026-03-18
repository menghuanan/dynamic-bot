package top.bilibili.service

import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

internal const val OKLCH_NEUTRAL_THRESHOLD = 0.02
internal const val HUE_EPSILON = 0.001
internal const val L_EPSILON = 0.0005

enum class NormalizationContext {
    USER_COMMAND,
    STARTUP_MIGRATION,
}

data class ColorNormalizationResult(
    val originalInput: String,
    val normalizedColor: String,
    val changed: Boolean,
)

data class ColorBindingResult(
    val success: Boolean,
    val message: String,
    val normalizedColor: String? = null,
    val changed: Boolean = false,
) {
    companion object {
        fun failure(message: String): ColorBindingResult = ColorBindingResult(
            success = false,
            message = message,
        )
    }
}

internal data class OklabColor(
    val l: Double,
    val a: Double,
    val b: Double,
)

internal data class OklchColor(
    var l: Double,
    var c: Double,
    var h: Double,
)

internal fun OklchColor.copyColor(): OklchColor = OklchColor(l, c, h)

internal fun OklchColor.toOklab(): OklabColor {
    val a = c * cos(Math.toRadians(h))
    val b = c * sin(Math.toRadians(h))
    return OklabColor(l, a, b)
}

fun normalizeSubjectScopedGradientColor(
    color: String,
    @Suppress("UNUSED_PARAMETER")
    context: NormalizationContext,
): ColorNormalizationResult? {
    val originalInput = normalizeGradientColorInput(color) ?: return null
    val normalizedColor = originalInput
        .split(';')
        .joinToString(";") { it.lowercase() }
    return ColorNormalizationResult(
        originalInput = originalInput,
        normalizedColor = normalizedColor,
        changed = normalizedColor != originalInput,
    )
}

internal fun encodeStop(oklch: OklchColor): String {
    val rgb = oklchToRgb(oklch)
    return "#%02x%02x%02x".format(rgb[0], rgb[1], rgb[2]).lowercase()
}

internal fun wrapHue(hue: Double): Double {
    var value = hue % 360.0
    if (value < 0.0) value += 360.0
    return value
}

internal fun shortestHueDeltaDegrees(from: Double, to: Double): Double {
    var delta = (to - from) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return delta
}

internal fun srgbHexToOklch(hex: String): OklchColor {
    val rgb = intArrayOf(
        hex.substring(1, 3).toInt(16),
        hex.substring(3, 5).toInt(16),
        hex.substring(5, 7).toInt(16),
    )
    val linear = rgb.map { srgbToLinear(it / 255.0) }
    val lab = linearRgbToOklab(linear[0], linear[1], linear[2])
    val c = sqrt(lab.a * lab.a + lab.b * lab.b)
    val h = wrapHue(Math.toDegrees(atan2(lab.b, lab.a)))
    return OklchColor(lab.l, c, h)
}

internal fun oklchToRgb(color: OklchColor): IntArray {
    val a = color.c * cos(Math.toRadians(color.h))
    val b = color.c * sin(Math.toRadians(color.h))
    val linear = oklabToLinearRgb(OklabColor(color.l, a, b))
    return intArrayOf(
        linearToSrgb(linear[0]),
        linearToSrgb(linear[1]),
        linearToSrgb(linear[2]),
    )
}

internal fun linearRgbToOklab(r: Double, g: Double, b: Double): OklabColor {
    val l = 0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b
    val m = 0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b
    val s = 0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b

    val lRoot = cbrt(l)
    val mRoot = cbrt(m)
    val sRoot = cbrt(s)

    return OklabColor(
        l = 0.2104542553 * lRoot + 0.7936177850 * mRoot - 0.0040720468 * sRoot,
        a = 1.9779984951 * lRoot - 2.4285922050 * mRoot + 0.4505937099 * sRoot,
        b = 0.0259040371 * lRoot + 0.7827717662 * mRoot - 0.8086757660 * sRoot,
    )
}

internal fun oklabToLinearRgb(color: OklabColor): DoubleArray {
    val lRoot = color.l + 0.3963377774 * color.a + 0.2158037573 * color.b
    val mRoot = color.l - 0.1055613458 * color.a - 0.0638541728 * color.b
    val sRoot = color.l - 0.0894841775 * color.a - 1.2914855480 * color.b

    val l = lRoot * lRoot * lRoot
    val m = mRoot * mRoot * mRoot
    val s = sRoot * sRoot * sRoot

    return doubleArrayOf(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s,
    )
}

internal fun srgbToLinear(value: Double): Double {
    return if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
}

internal fun linearToSrgb(value: Double): Int {
    val clamped = value.coerceIn(0.0, 1.0)
    val encoded = if (clamped <= 0.0031308) {
        clamped * 12.92
    } else {
        1.055 * clamped.pow(1.0 / 2.4) - 0.055
    }
    return (encoded * 255.0).toInt().coerceIn(0, 255)
}
