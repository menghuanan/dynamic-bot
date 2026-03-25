package top.bilibili.service

import org.jetbrains.skia.Color
import top.bilibili.BiliConfigManager
import top.bilibili.api.*
import top.bilibili.data.*
import top.bilibili.draw.*
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.*

suspend fun matchingRegular(content: String, subject: String? = null): ResolvedLinkInfo? {
    return matchingInternalRegular(content, subject)
}

suspend fun matchingAllRegular(content: String, subject: String? = null): List<ResolvedLinkInfo> {
    return matchingAllRegularOrdered(content, subject)
}

private suspend fun matchingAllRegularOrdered(content: String, subject: String? = null): List<ResolvedLinkInfo> {
    val processedIds = mutableSetOf<String>()
    val results = mutableListOf<ResolvedLinkInfo>()

    for (orderedMatch in collectOrderedMatches(content)) {
        val info = resolveLinkMatch(orderedMatch.type, orderedMatch.matchResult, subject) ?: continue
        val uniqueId = "${info.type.stableName}:${info.id}"
        if (processedIds.add(uniqueId)) {
            results.add(info)
        }
    }

    return results
}

private data class OrderedLinkMatch(
    val type: LinkType,
    val matchResult: MatchResult
) {
    val startIndex: Int get() = matchResult.range.first
}

private fun prioritizedLinkTypes(): List<LinkType> {
    return listOf(LinkType.ShortLink) + LinkType.matchableTypes().filter { it != LinkType.ShortLink }
}

private fun collectOrderedMatches(content: String): List<OrderedLinkMatch> {
    val prioritizedTypes = prioritizedLinkTypes()
    val matches = mutableListOf<OrderedLinkMatch>()

    for (linkType in prioritizedTypes) {
        for (regex in linkType.regex) {
            var matchResult = regex.find(content)
            while (matchResult != null) {
                matches += OrderedLinkMatch(linkType, matchResult)
                matchResult = matchResult.next()
            }
        }
    }

    return matches.sortedWith(
        compareBy<OrderedLinkMatch> { it.startIndex }
            .thenBy { prioritizedTypes.indexOf(it.type) }
    )
}

data class ResolvedLinkInfo(val type: LinkType, val id: String, val subject: String? = null) : ResolveLink {
    // 保留这一层转发，既要继续透传 subject，也要满足源码回归测试对 `type.drawGeneral(id, subject)` 形态的约束，后续整理时不要内联掉。
    override suspend fun drawGeneral(): String? = type.drawGeneral(id, subject)
    override suspend fun getLink(): String = type.getLink(id)
}

suspend fun matchingInternalRegular(content: String, subject: String? = null): ResolvedLinkInfo? {
    var matchResult: MatchResult? = null
    var type: LinkType? = null

    val prioritizedTypes = listOf(LinkType.ShortLink) +
        LinkType.matchableTypes().filter { it != LinkType.ShortLink }

    for (linkType in prioritizedTypes) {
        for (regex in linkType.regex) {
            matchResult = regex.find(content)
            if (matchResult != null) {
                type = linkType
                break
            }
        }
        if (matchResult != null) break
    }

    if (matchResult == null || type == null) {
        logger.warn("未匹配到链接! -> $content")
        return null
    }

    return resolveLinkMatch(type, matchResult, subject)
}

private suspend fun resolveLinkMatch(
    type: LinkType,
    matchResult: MatchResult,
    subject: String? = null
): ResolvedLinkInfo? {
    val id = matchResult.destructured.component1()

    if (type == LinkType.ShortLink) {
        val realLink = biliClient.redirect("https://b23.tv/$id")
        if (realLink != null) {
            logger.info("短链接 https://b23.tv/$id 解析为: $realLink")
            return matchingInternalRegular(realLink, subject)
        }

        logger.warn("短链接解析失败: https://b23.tv/$id")
        return null
    }

    if (type == LinkType.Dynamic && isOpusMatch(matchResult)) {
        return ResolvedLinkInfo(LinkType.OpusWithCv(opusId = id), id, subject)
    }

    return ResolvedLinkInfo(type, id, subject)
}

private fun isOpusMatch(matchResult: MatchResult): Boolean {
    return matchResult.value.contains("/opus/")
}

enum class TriggerMode {
    At,
    Always,
    Never
}

interface ResolveLink {
    suspend fun drawGeneral(): String?
    suspend fun getLink(): String
}

sealed class LinkType(val regex: List<Regex> = emptyList()) {
    abstract val stableName: String

    // 这两个兼容 shim 是给 ResolvedLinkInfo 和源码守卫用的；实际实现仍在下方顶层函数里，后续重构时不要删除这层委托。
    suspend fun drawGeneral(id: String, subject: String? = null): String? = drawResolvedLink(this, id, subject)

    suspend fun getLink(id: String): String = resolveCanonicalLink(this, id)

    object VideoLink : LinkType(listOf(
        """(?:www\.bilibili\.com/video/)?((?:BV[0-9A-z]{10})|(?:av\d{1,20}))""".toRegex()
    )) {
        override val stableName: String = "VideoLink"
    }

    object Article : LinkType(listOf(
        """(?:www\.bilibili\.com/read/)?cv(\d{1,10})""".toRegex(),
        """(?:www\.bilibili\.com/read/mobile/)(\d{1,10})""".toRegex()
    )) {
        override val stableName: String = "Article"
    }

    object Dynamic : LinkType(listOf(
        """[tm]\.bilibili\.com/(?:dynamic/)?(\d+)""".toRegex(),
        """(?:www|m)\.bilibili\.com/opus/(\d+)""".toRegex()
    )) {
        override val stableName: String = "Dynamic"
    }

    object Live : LinkType(listOf(
        """live\.bilibili\.com/(?:h5/)?(\d+)""".toRegex()
    )) {
        override val stableName: String = "Live"
    }

    object User : LinkType(listOf(
        """space\.bilibili\.com/(\d+)""".toRegex()
    )) {
        override val stableName: String = "User"
    }

    object Pgc : LinkType(listOf(
        """(?:(?:www|m)\.bilibili\.com/bangumi/(?:play|media)/)?((?:ss|ep|md)\d+)""".toRegex()
    )) {
        override val stableName: String = "Pgc"
    }

    object ShortLink : LinkType(listOf(
        """(?:b23\.tv|bili2233\.cn)\\?/([0-9A-z]+)""".toRegex()
    )) {
        override val stableName: String = "ShortLink"
    }

    data class OpusWithCv(val opusId: String, val cvId: String? = null) : LinkType() {
        override val stableName: String = "OpusWithCv"
    }

    companion object {
        fun matchableTypes(): List<LinkType> = listOf(VideoLink, Article, Dynamic, Live, User, Pgc, ShortLink)
    }
}

private suspend fun drawResolvedLink(type: LinkType, id: String, subject: String? = null): String? {
    if (!FeatureSwitchService.canRenderLinkResolveDraw()) return null
    return when (type) {
        LinkType.VideoLink -> drawVideoLink(id, subject)
        LinkType.Article -> drawArticleLink(id, subject)
        LinkType.Dynamic -> drawDynamicLink(id, subject)
        LinkType.Live -> drawLiveLink(id, subject)
        LinkType.User -> drawUserLink(id, subject)
        LinkType.Pgc -> drawPgcLink(id)
        LinkType.ShortLink -> drawShortLink(id, subject)
        is LinkType.OpusWithCv -> drawDynamicLink(type.opusId, subject, type.cvId)
    }
}

private suspend fun drawVideoLink(id: String, subject: String? = null): String? {
    return biliClient.getVideoDetail(id)?.run {
        val author = biliClient.userInfo(owner.mid)?.toDrawAuthorData() ?: toDrawAuthorData()
        val videoData = toDrawData()
        val palette = resolveGradientPalette(author.mid, subject)
        val footer = buildFooter(author.name, author.mid, id, pubdate.formatRelativeTime, "视频")

        SkiaManager.executeDrawing {
            val authorImg = author.drawGeneral(this, pubdate.formatRelativeTime, VIDEO_LINK(id), palette.themeColor)
            val contentImg = videoData.drawGeneral(this, true)
            val imgList = listOf(authorImg, contentImg)
            val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
            val img = makeCardBg(this, cimg.height, palette.backgroundColors) { it.drawImage(cimg, 0f, 0f) }
            cacheImage(
                img,
                DrawCacheKeyService.searchPath(uid = author.mid, entityType = "video", entityId = id, subject = subject, color = palette.source.color),
                CacheType.DRAW_SEARCH
            )
        }
    }
}

private suspend fun drawArticleLink(id: String, subject: String? = null): String? {
    val opusId = runCatching { resolveArticleDynamicId(id) }.getOrNull()
    if (opusId != null) {
        return drawDynamicLink(opusId, subject, articleIdOverride = id)
    }

    return biliClient.getArticleDetail("cv$id")?.run {
        val articleData = toDrawData()
        val palette = resolveGradientPalette(author.mid, subject)
        val footer = buildFooter(author.name, author.mid, id, time.formatRelativeTime, "专栏")

        SkiaManager.executeDrawing {
            val authorImg = author.drawGeneral(this, time.formatRelativeTime, ARTICLE_LINK(id), palette.themeColor)
            val contentImg = articleData.drawGeneral(this)
            val imgList = listOf(authorImg, contentImg)
            val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
            val img = makeCardBg(this, cimg.height, palette.backgroundColors) { it.drawImage(cimg, 0f, 0f) }
            cacheImage(
                img,
                DrawCacheKeyService.searchPath(uid = author.mid, entityType = "article", entityId = id, subject = subject, color = palette.source.color),
                CacheType.DRAW_SEARCH
            )
        }
    }
}

private suspend fun resolveArticleDynamicId(id: String): String? {
    return biliClient.getArticleView(id)?.dynIdStr?.takeIf { it.isNotBlank() }
}

private suspend fun drawDynamicLink(id: String, subject: String? = null, articleIdOverride: String? = null): String? {
    return biliClient.getDynamicDetail(id)?.run {
        if (articleIdOverride != null) {
            normalizeArticleOpusDisplayTree(articleIdOverride = articleIdOverride)
        } else {
            normalizeArticleOpusDisplayTree()
        }
        val palette = resolveGradientPalette(modules.moduleAuthor.mid, subject)
        SkiaManager.executeDrawing {
            val dynamic = this@run.drawDynamic(this, palette.themeColor)
            val img = makeCardBg(this, dynamic.height, palette.backgroundColors) {
                it.drawImage(dynamic, 0f, 0f)
            }
            cacheImage(
                img,
                DrawCacheKeyService.searchPath(
                    uid = modules.moduleAuthor.mid,
                    entityType = "dynamic",
                    entityId = idStr ?: id,
                    subject = subject,
                    color = palette.source.color
                ),
                CacheType.DRAW_SEARCH
            )
        }
    }
}

private suspend fun drawLiveLink(id: String, subject: String? = null): String? {
    val room = biliClient.getLiveDetail(id) ?: return null
    val author = biliClient.userInfo(room.uid)?.toDrawAuthorData() ?: return null
    val liveData = room.toDrawData()
    val palette = resolveGradientPalette(room.uid, subject)
    val area = if (room.parentAreaName != null && room.areaName != null) {
        "${room.parentAreaName} 路 ${room.areaName}"
    } else {
        room.areaName ?: ""
    }
    val liveStatusText = when (room.liveStatus) {
        0 -> "未开播"
        1 -> "直播中"
        2 -> "轮播中"
        else -> "直播"
    }
    val footer = run {
        val footerTemplate = BiliConfigManager.config.templateConfig.footer.liveFooter
        if (footerTemplate.isNotBlank()) {
            footerTemplate
                .replace("{name}", author.name)
                .replace("{uid}", author.mid.toString())
                .replace("{id}", id)
                .replace("{time}", author.sign ?: "")
                .replace("{type}", liveStatusText)
                .replace("{area}", area)
        } else null
    }

    return SkiaManager.executeDrawing {
        val authorImg = author.drawGeneral(this, author.sign ?: "", LIVE_LINK(id), palette.themeColor)
        val contentImg = liveData.drawGeneral(this)
        val imgList = listOf(authorImg, contentImg)
        val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
        val img = makeCardBg(this, cimg.height, palette.backgroundColors) { it.drawImage(cimg, 0f, 0f) }
        cacheImage(
            img,
            DrawCacheKeyService.searchPath(uid = room.uid, entityType = "live", entityId = id, subject = subject, color = palette.source.color),
            CacheType.DRAW_SEARCH
        )
    }
}

private suspend fun drawUserLink(id: String, subject: String? = null): String? {
    val author = biliClient.userInfo(id.toLong())?.toDrawAuthorData() ?: return null
    val palette = resolveGradientPalette(author.mid, subject)
    val footer = buildFooter(author.name, author.mid, id, author.sign ?: "", "用户")

    return SkiaManager.executeDrawing {
        val authorImg = author.drawGeneral(this, author.sign ?: "", SPACE_LINK(id), palette.themeColor)
        val imgList = listOf(authorImg)
        val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
        val img = makeCardBg(this, cimg.height, palette.backgroundColors) { it.drawImage(cimg, 0f, 0f) }
        cacheImage(
            img,
            DrawCacheKeyService.searchPath(uid = author.mid, entityType = "user", entityId = id, subject = subject, color = palette.source.color),
            CacheType.DRAW_SEARCH
        )
    }
}

private suspend fun drawPgcLink(id: String): String? {
    val info = biliClient.getPcgInfo(id) ?: return null
    val pgcData = info.toPgc() ?: return null
    val colors = getDefaultColors()

    return SkiaManager.executeDrawing {
        val contentImg = pgcData.drawSmall(this)
        val imgList = listOf(contentImg)
        val cimg = imgList.assembleCard(this, id, tag = "搜索", closeInputImages = true)
        val img = makeCardBg(this, cimg.height, colors) { it.drawImage(cimg, 0f, 0f) }
        cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
    }
}

private suspend fun drawShortLink(id: String, subject: String? = null): String? {
    val link = biliClient.redirect("https://b23.tv/$id")
    return if (link != null) {
        matchingInternalRegular(link, subject)?.drawGeneral()
    } else null
}

private suspend fun resolveCanonicalLink(type: LinkType, id: String): String {
    return when (type) {
        LinkType.VideoLink -> VIDEO_LINK(id)
        LinkType.Article -> ARTICLE_LINK(id)
        LinkType.Dynamic -> DYNAMIC_LINK(id)
        LinkType.Live -> LIVE_LINK(id)
        LinkType.User -> SPACE_LINK(id)
        LinkType.Pgc -> PGC_LINK(id)
        LinkType.ShortLink -> "$BASE_SHORT/$id"
        is LinkType.OpusWithCv -> type.cvId?.let { ARTICLE_LINK(it) } ?: OPUS_LINK(type.opusId)
    }
}

private fun getDefaultColors(): List<Int> {
    return parseGradientColors(BiliConfigManager.config.imageConfig.defaultColor)
}

fun VideoDetail.toDrawAuthorData(): ModuleAuthor =
    ModuleAuthor(
        "AUTHOR_TYPE_NORMAL",
        owner.mid,
        owner.name,
        owner.face
    )

fun BiliUser.toDrawAuthorData(): ModuleAuthor =
    ModuleAuthor(
        "AUTHOR_TYPE_NORMAL",
        mid,
        name!!,
        face!!,
        officialVerify = official,
        vip = vip,
        pendant = pendant,
        sign = sign
    )

fun VideoDetail.toDrawData(): ModuleDynamic.Major.Archive =
    ModuleDynamic.Major.Archive(
        0,
        aid,
        bvid,
        title,
        pic,
        desc,
        duration.formatDuration(false),
        "",
        ModuleDynamic.Major.Stat(
            stat.danmaku.toString(),
            stat.view.toString()
        ),
        ModuleDynamic.Major.Badge(
            "#fb7299",
            "#ffffff",
            "视频"
        )
    )

fun ArticleDetail.toDrawData(): ModuleDynamic.Major.Article =
    ModuleDynamic.Major.Article(
        aid.toString(),
        title,
        summary,
        "$words 字",
        "",
        covers
    )

fun LiveRoomDetail.toDrawData(): ModuleDynamic.Major.Live =
    ModuleDynamic.Major.Live(
        roomId,
        title,
        cover,
        "$parentAreaName",
        "$areaName",
        "",
        liveStatus,
        0,
        ModuleDynamic.Major.Badge(
            "",
            "",
            when (liveStatus) {
                0 -> "未开播"
                1 -> "直播中"
                2 -> "轮播中"
                else -> "直播"
            }
        )
    )

fun BiliDetail.toPgc(): ModuleDynamic.Major.Pgc? =
    when (this) {
        is PgcSeason -> {
            val typeName = when (type) {
                1 -> "番剧"
                2 -> "电影"
                3 -> "纪录片"
                4 -> "国创"
                5 -> "电视剧"
                7 -> "综艺"
                else -> "番剧"
            }
            ModuleDynamic.Major.Pgc(
                type,
                0,
                seasonId.toInt(),
                0,
                title,
                cover,
                "",
                ModuleDynamic.Major.Stat(
                    stat?.danmakus.toString(),
                    stat?.views.toString()
                ),
                ModuleDynamic.Major.Badge("", "", typeName),
                evaluate,
                areas?.joinToString(" / ") { it.name ?: "" },
                publish?.pubTimeShow,
                publish?.isFinish,
                total,
                rating?.score
            )
        }
        is PgcMedia -> {
            ModuleDynamic.Major.Pgc(
                media.type,
                0,
                media.seasonId.toInt(),
                0,
                media.title,
                media.horizontalPicture,
                "",
                ModuleDynamic.Major.Stat("", ""),
                ModuleDynamic.Major.Badge("", "", media.typeName),
                null,
                null,
                null,
                null,
                null
            )
        }

        else -> null
    }
