package top.bilibili.draw

import top.bilibili.BiliConfigManager
import kotlin.test.Test
import kotlin.test.assertEquals

class PgcCardLayoutRegressionTest {
    @Test
    fun `pgc card layout should reserve help-style top safe gap`() {
        BiliConfigManager.init()

        val contentHeight = 320f
        val layout = computePgcCardLayout(contentHeight)

        assertEquals(quality.cardPadding.toFloat(), layout.topSafeGap)
        assertEquals(quality.badgeHeight + quality.cardPadding.toFloat(), layout.cardTop)
        assertEquals(
            quality.badgeHeight + quality.cardPadding + contentHeight.toInt() + quality.cardPadding,
            layout.surfaceHeight
        )
    }
}
