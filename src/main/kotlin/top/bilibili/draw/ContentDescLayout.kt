package top.bilibili.draw

import top.bilibili.data.ModuleDynamic

/**
 * 将翻译文本追加到动态正文节点列表中，供统一绘制流程消费。
 *
 * @param richTextNodes 原始富文本节点
 * @param translation 翻译文本
 * @param cutLine 翻译分隔线
 */
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

/**
 * 将富文本节点展平成纯文本，供段落预布局时估算高度。
 *
 * @param nodes 富文本节点列表
 */
internal fun buildContentDescMeasureText(
    nodes: List<ModuleDynamic.ContentDesc.RichTextNode>
): String {
    return nodes.joinToString(separator = "") { it.text }
}
