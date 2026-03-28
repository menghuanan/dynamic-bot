package top.bilibili.service

import org.jetbrains.skia.Color
import top.bilibili.BiliConfigManager
import top.bilibili.api.*
import top.bilibili.data.*
import top.bilibili.draw.*
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.*

/**
 * 为旧调用方保留单链接解析入口，继续复用统一的匹配与去重链路。
 */
suspend fun matchingRegular(content: String, subject: String? = null): ResolvedLinkInfo? {
    return matchingInternalRegular(content, subject)
}

/**
 * 解析文本中的全部可识别链接，并保持结果顺序与正文出现顺序一致。
 */
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

/**
 * 封装已解析链接的类型、标识与会话信息，便于后续统一绘图和取规范链接。
 */
data class ResolvedLinkInfo(val type: LinkType, val id: String, val subject: String? = null) : ResolveLink {
    // 保留这一层转发，既要继续透传 subject，也要满足源码回归测试对 `type.drawGeneral(id, subject)` 形态的约束，后续整理时不要内联掉。
    /**
     * 复用链接类型上的绘图实现，并继续透传当前会话作用域。
     */
    override suspend fun drawGeneral(): String? = type.drawGeneral(id, subject)

    /**
     * 复用链接类型上的规范链接解析，避免调用方自行判断类型。
     */
    override suspend fun getLink(): String = type.getLink(id)
}

/**
 * 执行首个命中链接的解析，供需要单结果的旧调用路径继续复用。
 */
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

/**
 * 描述链接解析在当前消息中的触发策略，便于上层按配置决定是否响应。
 */
enum class TriggerMode {
    At,
    Always,
    Never
}

/**
 * 抽象可解析链接需要暴露的最小能力，避免消息处理链依赖具体链接类型。
 */
interface ResolveLink {
    /**
     * 生成当前链接的预览图路径；当条件不满足时允许返回空。
     */
    suspend fun drawGeneral(): String?

    /**
     * 返回当前链接的规范化跳转地址，供纯文本回退场景复用。
     */
    suspend fun getLink(): String
}

/**
 * 收口全部可识别链接类型及其兼容入口，避免正则和展示逻辑分散维护。
 */
sealed class LinkType(val regex: List<Regex> = emptyList()) {
    abstract val stableName: String

    // 这两个兼容 shim 是给 ResolvedLinkInfo 和源码守卫用的；实际实现仍在下方顶层函数里，后续重构时不要删除这层委托。
    /**
     * 为链接类型保留统一绘图入口，便于结果对象和测试共用同一调用形态。
     */
    suspend fun drawGeneral(id: String, subject: String? = null): String? = drawResolvedLink(this, id, subject)

    /**
     * 为链接类型保留统一规范链接入口，避免调用方拼接不同类型的 URL。
     */
    suspend fun getLink(id: String): String = resolveCanonicalLink(this, id)

    /**
     * 匹配普通视频链接与 av/BV 两种视频标识。
     */
    object VideoLink : LinkType(listOf(
        """(?:www\.bilibili\.com/video/)?((?:BV[0-9A-z]{10})|(?:av\d{1,20}))""".toRegex()
    )) {
        override val stableName: String = "VideoLink"
    }

    /**
     * 匹配专栏链接，并兼容桌面与移动页两种形态。
     */
    object Article : LinkType(listOf(
        """(?:www\.bilibili\.com/read/)?cv(\d{1,10})""".toRegex(),
        """(?:www\.bilibili\.com/read/mobile/)(\d{1,10})""".toRegex()
    )) {
        override val stableName: String = "Article"
    }

    /**
     * 匹配动态与 opus 链接，为后续动态卡片绘制提供统一入口。
     */
    object Dynamic : LinkType(listOf(
        """[tm]\.bilibili\.com/(?:dynamic/)?(\d+)""".toRegex(),
        """(?:www|m)\.bilibili\.com/opus/(\d+)""".toRegex()
    )) {
        override val stableName: String = "Dynamic"
    }

    /**
     * 匹配直播间链接，供直播搜索卡片和直链回退复用。
     */
    object Live : LinkType(listOf(
        """live\.bilibili\.com/(?:h5/)?(\d+)""".toRegex()
    )) {
        override val stableName: String = "Live"
    }

    /**
     * 匹配用户空间链接，统一转换为用户卡片搜索入口。
     */
    object User : LinkType(listOf(
        """space\.bilibili\.com/(\d+)""".toRegex()
    )) {
        override val stableName: String = "User"
    }

    /**
     * 匹配番剧相关链接，兼容 ss、ep、md 三种常见标识。
     */
    object Pgc : LinkType(listOf(
        """(?:(?:www|m)\.bilibili\.com/bangumi/(?:play|media)/)?((?:ss|ep|md)\d+)""".toRegex()
    )) {
        override val stableName: String = "Pgc"
    }

    /**
     * 匹配短链接并交给重定向解析，以便继续复用其他类型的完整链路。
     */
    object ShortLink : LinkType(listOf(
        """(?:b23\.tv|bili2233\.cn)\\?/([0-9A-z]+)""".toRegex()
    )) {
        override val stableName: String = "ShortLink"
    }

    /**
     * 标记 opus 链接及其可选专栏 ID，便于统一落到动态或专栏展示。
     */
    data class OpusWithCv(val opusId: String, val cvId: String? = null) : LinkType() {
        override val stableName: String = "OpusWithCv"
    }

    companion object {
        /**
         * 返回参与正文匹配的链接类型列表，集中维护匹配顺序依赖。
         */
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

/**
 * 将视频详情转换为统一作者展示结构，避免搜索绘图链路分别适配字段。
 */
fun VideoDetail.toDrawAuthorData(): ModuleAuthor =
    ModuleAuthor(
        "AUTHOR_TYPE_NORMAL",
        owner.mid,
        owner.name,
        owner.face
    )

/**
 * 将用户详情转换为统一作者展示结构，供空间链接和搜索卡片复用。
 */
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

/**
 * 将视频详情转换为动态卡片可直接渲染的主体数据结构。
 */
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

/**
 * 将专栏详情转换为动态卡片渲染结构，统一搜索卡片展示口径。
 */
fun ArticleDetail.toDrawData(): ModuleDynamic.Major.Article =
    ModuleDynamic.Major.Article(
        aid.toString(),
        title,
        summary,
        "$words 字",
        "",
        covers
    )

/**
 * 将直播间详情转换为动态卡片渲染结构，兼容直播状态和分区展示。
 */
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

/**
 * 将番剧详情转换为统一的 PGC 渲染结构，供搜索卡片直接复用。
 */
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
