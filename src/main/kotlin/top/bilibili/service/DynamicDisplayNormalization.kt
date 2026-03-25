package top.bilibili.service

import top.bilibili.data.DynamicItem
import top.bilibili.data.DynamicType
import top.bilibili.data.ModuleDynamic

internal fun DynamicItem.normalizeArticleOpusDisplayTree() {
    normalizeArticleOpusDisplay()
    orig?.normalizeArticleOpusDisplayTree()
}

private fun DynamicItem.normalizeArticleOpusDisplay() {
    val major = modules.moduleDynamic.major ?: return
    if (type != DynamicType.DYNAMIC_TYPE_ARTICLE || major.type != "MAJOR_TYPE_OPUS") return

    val opus = major.opus ?: return
    major.article = ModuleDynamic.Major.Article(
        basic.ridStr,
        opus.title.orEmpty(),
        opus.summary?.text.orEmpty(),
        "",
        "",
        opus.pics.map { it.src }
    )
    major.type = "MAJOR_TYPE_ARTICLE"
    major.opus = null
}
