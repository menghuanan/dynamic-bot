package top.bilibili.service

import org.jetbrains.skia.Color
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.draw.generateLinearGradient
import top.bilibili.draw.hsb2rgb
import top.bilibili.draw.makeRGB
import top.bilibili.draw.rgb2hsb
import kotlin.math.abs
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradientColorRenderingTest {
    @BeforeTest
    fun setupRuntimeConfig() {
        val configField = BiliConfigManager::class.java.getDeclaredField("config")
        configField.isAccessible = true
        configField.set(BiliConfigManager, BiliConfig())
    }

    @Test
    fun `subject scoped generator should exist and expand single color beyond legacy hsb path`() {
        val method = Class.forName("top.bilibili.service.GradientColorRenderingKt")
            .methods
            .firstOrNull { it.name == "generateSubjectScopedGradientColors" }

        assertNotNull(method, "subject scoped gradient generator should be available")

        val generated = method.invoke(null, "#ff0000") as IntArray
        val legacy = generateLinearGradient(listOf(Color.makeRGB("#ff0000")))

        assertTrue(generated.size > 3, "subject scoped single-color gradient should expand into multiple Skia stops")
        assertTrue(!generated.contentEquals(legacy), "subject scoped generator should no longer reuse the legacy HSB output")
    }

    @Test
    fun `subject scoped generator should be stable for normalized single color`() {
        val method = Class.forName("top.bilibili.service.GradientColorRenderingKt")
            .methods
            .firstOrNull { it.name == "generateSubjectScopedGradientColors" }

        assertNotNull(method, "subject scoped gradient generator should be available")

        val once = method.invoke(null, "#d3edfa") as IntArray
        val twice = method.invoke(null, "#d3edfa") as IntArray

        assertContentEquals(once, twice, "single-color render expansion should be deterministic")
    }

    @Test
    fun `subject scoped generator should preserve distinct hue families for vivid four-stop input`() {
        val generated = generateSubjectScopedGradientColors("#ff0000;#00ff00;#0000ff;#ffff00")

        assertHasHueNear(generated, "#ff0000", 14.0)
        assertHasHueNear(generated, "#00ff00", 18.0)
        assertHasHueNear(generated, "#0000ff", 18.0)
        assertHasHueNear(generated, "#ffff00", 18.0)
    }

    @Test
    fun `subject scoped generator should preserve warm and blue regions for the main manual sample`() {
        val generated = generateSubjectScopedGradientColors("#fde5b6;#3183ae;#ffffff")

        assertHasHueNear(generated, "#fde5b6", 18.0)
        assertHasHueNear(generated, "#3183ae", 18.0)
    }

    @Test
    fun `single color render expansion should reuse legacy hue fan control points for non-neutral inputs`() {
        val method = Class.forName("top.bilibili.service.GradientColorRenderingKt")
            .getDeclaredMethod("expandSingleColorRenderStops", OklchColor::class.java)
        method.isAccessible = true

        val main = srgbHexToOklch("#ff0000")
        val legacyStops = legacySingleHueFanStops("#ff0000")
        @Suppress("UNCHECKED_CAST")
        val renderStops = method.invoke(null, main.copyColor()) as List<OklchColor>

        assertEquals(3, renderStops.size)
        renderStops.zip(legacyStops).forEachIndexed { index, (actual, expected) ->
            assertTrue(
                abs(shortestHueDeltaDegrees(expected.h, actual.h)) < 1.0,
                "single-color stop $index should stay near the legacy hue fan"
            )
            assertEquals(expected.l, actual.l, 0.003)
            assertEquals(expected.c, actual.c, 0.003)
        }
    }

    @Test
    fun `multi color render should match legacy lockSB tuning for non-neutral stops and only tint neutral stops lightly`() {
        val method = Class.forName("top.bilibili.service.GradientColorRenderingKt")
            .getDeclaredMethod("buildMultiColorRenderStops", List::class.java)
        method.isAccessible = true

        val stops = listOf("#fde5b6", "#3183ae", "#ffffff").map(::srgbHexToOklch)
        val warmLegacy = legacyLockedOklch("#fde5b6")
        val blueLegacy = legacyLockedOklch("#3183ae")
        @Suppress("UNCHECKED_CAST")
        val renderStops = method.invoke(null, stops) as List<OklchColor>

        assertEquals(3, renderStops.size)
        assertEquals(warmLegacy.l, renderStops[0].l, 0.003)
        assertEquals(blueLegacy.l, renderStops[1].l, 0.003)
        assertEquals(stops[2].l, renderStops[2].l, 0.001)
        assertEquals(warmLegacy.c, renderStops[0].c, 0.001)
        assertEquals(blueLegacy.c, renderStops[1].c, 0.001)
        assertEquals(0.0025, renderStops[2].c, 0.0001)
        assertTrue(abs(shortestHueDeltaDegrees(stops[1].h, renderStops[2].h)) < 1.0, "white stop should inherit nearby hue only for rendering stability")
    }

    @Test
    fun `interpolation helpers should bias midpoint slightly darker than pure linear`() {
        val renderingClass = Class.forName("top.bilibili.service.GradientColorRenderingKt")
        val remapMethod = renderingClass.declaredMethods.firstOrNull { it.name == "remapInterpolationFactor" }
        val biasMethod = renderingClass.declaredMethods.firstOrNull { it.name == "applyInterpolationMidpointBias" }

        assertNotNull(remapMethod, "interpolation factor remap helper should exist")
        assertNotNull(biasMethod, "midpoint lightness bias helper should exist")
        remapMethod.isAccessible = true
        biasMethod.isAccessible = true

        val remapped = remapMethod.invoke(null, 0.5) as Double
        val remappedAgain = remapMethod.invoke(null, 0.5) as Double
        val biased = biasMethod.invoke(null, 0.8, 0.5) as Double

        assertEquals(remapped, remappedAgain, 0.000001)
        assertTrue(remapped < 0.5, "midpoint interpolation should lean slightly toward the darker side")
        assertTrue(remapped > 0.45, "midpoint interpolation should stay subtle")
        assertTrue(biased < 0.8, "midpoint lightness bias should darken the interpolated midpoint")
        assertTrue(biased > 0.75, "midpoint lightness bias should stay subtle")
    }

    private fun assertHasHueNear(colors: IntArray, hex: String, tolerance: Double) {
        val expected = srgbHexToOklch(hex)
        val matched = colors
            .map(::skiaColorToHex)
            .map(::srgbHexToOklch)
            .any { stop ->
                stop.c >= 0.02 && abs(shortestHueDeltaDegrees(expected.h, stop.h)) <= tolerance
            }
        assertTrue(matched, "generated gradient should include hue near $hex within ${tolerance}deg")
    }

    private fun skiaColorToHex(color: Int): String {
        return "#%02x%02x%02x".format(Color.getR(color), Color.getG(color), Color.getB(color))
    }

    private fun legacyLockedOklch(hex: String): OklchColor {
        val rgb = Color.makeRGB(hex)
        val hsb = rgb2hsb(Color.getR(rgb), Color.getG(rgb), Color.getB(rgb))
        val generator = BiliConfigManager.config.imageConfig.colorGenerator
        if (generator.lockSB) {
            hsb[1] = generator.saturation
            hsb[2] = generator.brightness
        }
        val tuned = hsb2rgb(hsb[0], hsb[1], hsb[2])
        return srgbHexToOklch("#%02x%02x%02x".format(tuned[0], tuned[1], tuned[2]))
    }

    private fun legacySingleHueFanStops(hex: String): List<OklchColor> {
        val rgb = Color.makeRGB(hex)
        val hsb = rgb2hsb(Color.getR(rgb), Color.getG(rgb), Color.getB(rgb))
        val generator = BiliConfigManager.config.imageConfig.colorGenerator
        if (generator.lockSB) {
            hsb[1] = generator.saturation
            hsb[2] = generator.brightness
        }
        var hue = (hsb[0] + generator.hueStep) % 360
        return buildList(3) {
            repeat(3) {
                val tuned = hsb2rgb(hue, hsb[1], hsb[2])
                add(srgbHexToOklch("#%02x%02x%02x".format(tuned[0], tuned[1], tuned[2])))
                hue = if (hue - generator.hueStep < 0) hue + 360 - generator.hueStep else hue - generator.hueStep
            }
        }
    }
}
