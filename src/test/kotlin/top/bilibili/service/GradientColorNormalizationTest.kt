package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GradientColorNormalizationTest {
    @Test
    fun `single color normalization should only canonicalize syntax`() {
        val result = normalizeSubjectScopedGradientColor("#FF0000", NormalizationContext.USER_COMMAND)

        assertNotNull(result)
        assertEquals("#ff0000", result.normalizedColor)
        assertTrue(result.changed)
        assertTrue(
            result.javaClass.methods.none { it.name == "getReasons" },
            "syntax-only normalization result should no longer expose obsolete harmonization reasons",
        )
        assertTrue(
            result.javaClass.methods.none { it.name == "getStopDiffs" },
            "syntax-only normalization result should no longer expose obsolete stop-diff metadata",
        )
    }

    @Test
    fun `multi color normalization should preserve original hue structure and only lowercase syntax`() {
        val result = normalizeSubjectScopedGradientColor("#FDE5B6;#3183AE;#FFFFFF", NormalizationContext.USER_COMMAND)

        assertNotNull(result)
        assertEquals("#fde5b6;#3183ae;#ffffff", result.normalizedColor)
        assertTrue(result.changed)
        assertTrue(
            result.javaClass.methods.none { it.name == "getReasons" },
            "multi-color canonicalization should not carry legacy harmonization fields",
        )
        assertTrue(
            result.javaClass.methods.none { it.name == "getStopDiffs" },
            "multi-color canonicalization should not carry legacy component-diff fields",
        )
    }
}
