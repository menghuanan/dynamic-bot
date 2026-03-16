package top.bilibili.draw

import top.bilibili.data.ModuleDynamic
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentDescLayoutTest {
    @Test
    fun `measurement text should not contain cutLine when translation is absent`() {
        val node = ModuleDynamic.ContentDesc.RichTextNode(
            type = "RICH_TEXT_NODE_TYPE_TEXT",
            origText = "原文",
            text = "原文",
        )

        val nodes = buildContentDescRenderNodes(listOf(node), translation = null, cutLine = "\nCUT\n")
        val measureText = buildContentDescMeasureText(nodes)

        assertFalse(measureText.contains("CUT"))
    }

    @Test
    fun `measurement text should contain cutLine when translation exists`() {
        val node = ModuleDynamic.ContentDesc.RichTextNode(
            type = "RICH_TEXT_NODE_TYPE_TEXT",
            origText = "原文",
            text = "原文",
        )

        val nodes = buildContentDescRenderNodes(listOf(node), translation = "译文", cutLine = "\nCUT\n")
        val measureText = buildContentDescMeasureText(nodes)

        assertTrue(measureText.contains("CUT"))
        assertTrue(measureText.contains("译文"))
    }
}
