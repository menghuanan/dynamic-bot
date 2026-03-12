package top.bilibili.draw

import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Font
import org.jetbrains.skia.Rect
import org.jetbrains.skia.RRect
import org.jetbrains.skia.TextLine
import top.bilibili.BiliConfigManager
import top.bilibili.loadFonts
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicMediaLabelBaselineTest {
    @Test
    fun `dynamic media label baseline should match centered formula after font loading`() = runBlocking {
        BiliConfigManager.init()
        loadFonts()

        val labelFont = Font(mainTypeface, quality.subTitleFontSize)
        val textLine = TextLine.make("¶¯Í¼", labelFont)
        try {
            val rrect = RRect.makeXYWH(
                0f,
                0f,
                textLine.width + quality.badgePadding * 4f,
                textLine.height + quality.badgePadding / 2f,
                quality.badgeArc
            )
            val expected = Rect.makeXYWH(rrect.left, rrect.top, rrect.width, rrect.height).textVertical(textLine)

            assertEquals(expected, dynamicMediaLabelTextBaseline(rrect, textLine))
        } finally {
            textLine.close()
            labelFont.close()
        }
    }
}
