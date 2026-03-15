package top.bilibili.draw

import top.bilibili.BiliConfigManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PgcCardLayoutRegressionTest {
    @Test
    fun `pgc card layout should compensate outer content space and preserve symmetry`() {
        BiliConfigManager.init()

        val contentHeight = 320f
        val layout = computePgcCardLayout(contentHeight)
        val expectedBottomSafeGap = maxOf(
            quality.cardPadding.toFloat() - quality.contentSpace.toFloat(),
            theme.smallCardShadow.offsetY + 1f
        )

        assertEquals(quality.cardPadding.toFloat(), layout.topSafeGap)
        assertEquals(expectedBottomSafeGap, layout.bottomSafeGap)
        assertEquals(quality.badgeHeight + quality.cardPadding.toFloat(), layout.cardTop)
        assertEquals(
            quality.badgeHeight + quality.cardPadding + contentHeight.toInt() + expectedBottomSafeGap.toInt(),
            layout.surfaceHeight
        )
        assertEquals(quality.cardPadding.toFloat(), layout.bottomSafeGap + quality.contentSpace.toFloat())
    }

    @Test
    fun `pgc text block height helper should match render order without overcounting bottom padding`() {
        BiliConfigManager.init()

        val titleHeight = 68f
        val broadcastHeight = 24f
        val statusHeight = 24f
        val statHeight = 22f
        val evaluateHeight = 58f
        val halfGap = quality.cardPadding / 2f
        val expectedBaseHeight = titleHeight +
            broadcastHeight +
            statusHeight +
            (statHeight + quality.badgePadding.toFloat()) +
            halfGap * 4f

        val withoutEvaluate = computePgcTextBlockHeight(
            titleHeight = titleHeight,
            broadcastHeight = broadcastHeight,
            statusHeight = statusHeight,
            statHeight = statHeight,
            evaluateHeight = null
        )
        val withEvaluate = computePgcTextBlockHeight(
            titleHeight = titleHeight,
            broadcastHeight = broadcastHeight,
            statusHeight = statusHeight,
            statHeight = statHeight,
            evaluateHeight = evaluateHeight
        )
        val legacyWithEvaluate = titleHeight +
            broadcastHeight +
            statusHeight +
            statHeight +
            evaluateHeight +
            quality.cardPadding * 4f

        assertEquals(expectedBaseHeight, withoutEvaluate)
        assertEquals(expectedBaseHeight + evaluateHeight, withEvaluate)
        assertTrue(withEvaluate < legacyWithEvaluate)
    }
}