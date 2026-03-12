package top.bilibili.draw

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DynamicMediaLabelRuleTest {
    @Test
    fun `resolveDynamicMediaLabel should prefer gif label`() {
        assertEquals("动图", resolveDynamicMediaLabel("https://example.com/test.gif", width = 100, height = 400))
    }

    @Test
    fun `resolveDynamicMediaLabel should mark tall images`() {
        assertEquals("长图", resolveDynamicMediaLabel("https://example.com/test.png", width = 100, height = 201))
    }

    @Test
    fun `resolveDynamicMediaLabel should skip normal images`() {
        assertNull(resolveDynamicMediaLabel("https://example.com/test.png", width = 100, height = 200))
    }
}
