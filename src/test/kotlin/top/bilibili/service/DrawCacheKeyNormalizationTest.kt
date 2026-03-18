package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class DrawCacheKeyNormalizationTest {
    @Test
    fun `cache path should hash equivalent colors by normalized lower-case value`() {
        val uppercase = DrawCacheKeyService.dynamicPath(1L, "2", "group:10001", "#FF0000")
        val lowercase = DrawCacheKeyService.dynamicPath(1L, "2", "group:10001", "#ff0000")

        assertEquals(lowercase, uppercase, "cache key should deduplicate equivalent color inputs")
    }

    @Test
    fun `subject scoped normalization should be idempotent for stored color strings`() {
        val once = normalizeSubjectScopedGradientColor("#FF0000", NormalizationContext.USER_COMMAND)
        val twice = once?.normalizedColor?.let {
            normalizeSubjectScopedGradientColor(it, NormalizationContext.USER_COMMAND)
        }

        assertNotNull(once)
        assertNotNull(twice)
        assertEquals(once.normalizedColor, twice.normalizedColor)
    }
}
