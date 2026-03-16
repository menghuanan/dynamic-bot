package top.bilibili.draw

import top.bilibili.data.ModuleDynamic

internal fun buildContentDescRenderNodes(
    richTextNodes: List<ModuleDynamic.ContentDesc.RichTextNode>,
    translation: String?,
    cutLine: String,
): List<ModuleDynamic.ContentDesc.RichTextNode> {
    if (translation.isNullOrBlank()) {
        return richTextNodes
    }

    return richTextNodes +
        ModuleDynamic.ContentDesc.RichTextNode(
            type = "RICH_TEXT_NODE_TYPE_TEXT",
            origText = cutLine,
            text = cutLine,
        ) +
        ModuleDynamic.ContentDesc.RichTextNode(
            type = "RICH_TEXT_NODE_TYPE_TEXT",
            origText = translation,
            text = translation,
        )
}

internal fun buildContentDescMeasureText(
    nodes: List<ModuleDynamic.ContentDesc.RichTextNode>
): String {
    return nodes.joinToString(separator = "") { it.text }
}