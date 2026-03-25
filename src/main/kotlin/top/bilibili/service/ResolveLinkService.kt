package top.bilibili.service

import io.ktor.client.call.body
import io.ktor.client.request.get
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
        val uniqueId = "${info.type.name}:${info.id}"
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
    return listOf(LinkType.ShortLink) + LinkType.values().filter { it != LinkType.ShortLink }
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
    override suspend fun drawGeneral(): String? = type.drawGeneral(id, subject)
    override suspend fun getLink(): String = type.getLink(id)
}

suspend fun matchingInternalRegular(content: String, subject: String? = null): ResolvedLinkInfo? {
    var matchResult: MatchResult? = null
    var type: LinkType? = null


    val prioritizedTypes = listOf(LinkType.ShortLink) +
        LinkType.values().filter { it != LinkType.ShortLink }

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
        logger.warn("\u672a\u5339\u914d\u5230\u94fe\u63a5! -> $content")
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
            logger.info("\u77ed\u94fe\u63a5 https://b23.tv/$id \u89e3\u6790\u4e3a: $realLink")
            return matchingInternalRegular(realLink, subject)
        }

        logger.warn("\u77ed\u94fe\u63a5\u89e3\u6790\u5931\u8d25: https://b23.tv/$id")
        return null
    }

    if (type == LinkType.Dynamic && isOpusMatch(matchResult)) {
        val opusUrl = normalizeOpusUrl(matchResult.value)
        val cvId = resolveOpusCvId(opusUrl)
        if (cvId != null) {
            return ResolvedLinkInfo(LinkType.Article, cvId, subject)
        }
    }

    return ResolvedLinkInfo(type, id, subject)
}

private fun isOpusMatch(matchResult: MatchResult): Boolean {
    return matchResult.value.contains("/opus/")
}

private fun normalizeOpusUrl(raw: String): String {
    return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
}

private suspend fun resolveOpusCvId(opusUrl: String): String? {
    return runCatching {
        val html = biliClient.useHttpClient { it.get(opusUrl).body<String>() }
        Regex("cv(\\d{1,10})").find(html)?.groupValues?.get(1)
    }.getOrNull()
}

enum class TriggerMode {
    At,
    Always,
    Never
}

interface ResolveLink {
    //suspend fun resolve(): BiliDetail?
    suspend fun drawGeneral(): String?
    suspend fun getLink(): String
}

enum class LinkType(val regex: List<Regex>) {
    VideoLink(listOf(
        """(?:www\.bilibili\.com/video/)?((?:BV[0-9A-z]{10})|(?:av\d{1,20}))""".toRegex()
    )),
    Article(listOf(
        """(?:www\.bilibili\.com/read/)?cv(\d{1,10})""".toRegex(),
        """(?:www\.bilibili\.com/read/mobile/)(\d{1,10})""".toRegex()
    )),
    Dynamic(listOf(
        """[tm]\.bilibili\.com/(?:dynamic/)?(\d+)""".toRegex(),
        """(?:www|m)\.bilibili\.com/opus/(\d+)""".toRegex()
    )),
    Live(listOf(
        """live\.bilibili\.com/(?:h5/)?(\d+)""".toRegex()
    )),
    User(listOf(
        """space\.bilibili\.com/(\d+)""".toRegex()
    )),
    Pgc(listOf(
        """(?:(?:www|m)\.bilibili\.com/bangumi/(?:play|media)/)?((?:ss|ep|md)\d+)""".toRegex()
    )),
    ShortLink(listOf(
        """(?:b23\.tv|bili2233\.cn)\\?/([0-9A-z]+)""".toRegex()
    ));

    suspend fun drawGeneral(id: String, subject: String? = null): String? {
        if (!FeatureSwitchService.canRenderLinkResolveDraw()) return null
        return when (this) {
            VideoLink -> {
                biliClient.getVideoDetail(id)?.run {
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
                        cacheImage(img, DrawCacheKeyService.searchPath(uid = author.mid, entityType = "video", entityId = id, subject = subject, color = palette.source.color), CacheType.DRAW_SEARCH)
                    }
                }
            }
            Article -> {
                biliClient.getArticleDetail("cv$id")?.run {
                    val articleData = toDrawData()
                    val palette = resolveGradientPalette(author.mid, subject)
                    val footer = buildFooter(author.name, author.mid, id, time.formatRelativeTime, "专栏")

                    SkiaManager.executeDrawing {
                        val authorImg = author.drawGeneral(this, time.formatRelativeTime, ARTICLE_LINK(id), palette.themeColor)
                        val contentImg = articleData.drawGeneral(this)
                        val imgList = listOf(authorImg, contentImg)
                        val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
                        val img = makeCardBg(this, cimg.height, palette.backgroundColors) { it.drawImage(cimg, 0f, 0f) }
                        cacheImage(img, DrawCacheKeyService.searchPath(uid = author.mid, entityType = "article", entityId = id, subject = subject, color = palette.source.color), CacheType.DRAW_SEARCH)
                    }
                }
            }
            Dynamic -> {
                biliClient.getDynamicDetail(id)?.run {
                    normalizeArticleOpusDisplayTree()
                    val palette = resolveGradientPalette(modules.moduleAuthor.mid, subject)
                    SkiaManager.executeDrawing {
                        val dynamic = this@run.drawDynamic(this, palette.themeColor)
                        val img = makeCardBg(this, dynamic.height, palette.backgroundColors) {
                            it.drawImage(dynamic, 0f, 0f)
                        }
                        cacheImage(img, DrawCacheKeyService.searchPath(uid = modules.moduleAuthor.mid, entityType = "dynamic", entityId = idStr ?: id, subject = subject, color = palette.source.color), CacheType.DRAW_SEARCH)
                    }
                }
            }
            Live -> {
                val room = biliClient.getLiveDetail(id) ?: return null
                val author = biliClient.userInfo(room.uid)?.toDrawAuthorData() ?: return null
                val liveData = room.toDrawData()
                val palette = resolveGradientPalette(room.uid, subject)
                val area = if (room.parentAreaName != null && room.areaName != null) {
                    "${room.parentAreaName} · ${room.areaName}"
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

                SkiaManager.executeDrawing {
                    val authorImg = author.drawGeneral(this, author.sign ?: "", LIVE_LINK(id), palette.themeColor)
                    val contentImg = liveData.drawGeneral(this)
                    val imgList = listOf(authorImg, contentImg)
                    val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
                    val img = makeCardBg(this, cimg.height, palette.backgroundColors) { it.drawImage(cimg, 0f, 0f) }
                    cacheImage(img, DrawCacheKeyService.searchPath(uid = room.uid, entityType = "live", entityId = id, subject = subject, color = palette.source.color), CacheType.DRAW_SEARCH)
                }
            }
            User -> {
                val author = biliClient.userInfo(id.toLong())?.toDrawAuthorData() ?: return null
                val palette = resolveGradientPalette(author.mid, subject)
                val footer = buildFooter(author.name, author.mid, id, author.sign ?: "", "用户")

                SkiaManager.executeDrawing {
                    val authorImg = author.drawGeneral(this, author.sign ?: "", SPACE_LINK(id), palette.themeColor)
                    val imgList = listOf(authorImg)
                    val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
                    val img = makeCardBg(this, cimg.height, palette.backgroundColors) { it.drawImage(cimg, 0f, 0f) }
                    cacheImage(img, DrawCacheKeyService.searchPath(uid = author.mid, entityType = "user", entityId = id, subject = subject, color = palette.source.color), CacheType.DRAW_SEARCH)
                }
            }
            Pgc -> {
                val info = biliClient.getPcgInfo(id) ?: return null
                val pgcData = info.toPgc() ?: return null
                val colors = getDefaultColors()

                SkiaManager.executeDrawing {
                    val contentImg = pgcData.drawSmall(this)
                    val imgList = listOf(contentImg)
                    val cimg = imgList.assembleCard(this, id, tag = "搜索", closeInputImages = true)
                    val img = makeCardBg(this, cimg.height, colors) { it.drawImage(cimg, 0f, 0f) }
                    cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
                }
            }
            ShortLink -> {
                val link = biliClient.redirect("https://b23.tv/$id")
                if (link != null) {
                    matchingInternalRegular(link, subject)?.drawGeneral()
                } else null
            }
        }
    }

    suspend fun getLink(id: String): String {
        return when (this) {
            VideoLink -> VIDEO_LINK(id)
            Article -> ARTICLE_LINK(id)
            Dynamic -> DYNAMIC_LINK(id)
            Live -> LIVE_LINK(id)
            User -> SPACE_LINK(id)
            Pgc -> PGC_LINK(id)
            ShortLink -> "$BASE_SHORT/$id"
        }
    }
}

private fun getDefaultColors(): List<Int> {
    return parseGradientColors(BiliConfigManager.config.imageConfig.defaultColor)
}

//摆烂行为

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
            "", "",
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
