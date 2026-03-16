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
    val results = mutableListOf<ResolvedLinkInfo>()
    val processedIds = mutableSetOf<String>()

    // 定义优先级顺序：短链接优先，其他保持原顺序
    val prioritizedTypes = listOf(LinkType.ShortLink) +
        LinkType.values().filter { it != LinkType.ShortLink }

    for (linkType in prioritizedTypes) {
        for (regex in linkType.regex) {
            var matchResult = regex.find(content)
            while (matchResult != null) {
                val id = matchResult.destructured.component1()
                val uniqueId = "${linkType.name}:$id"

                if (!processedIds.contains(uniqueId)) {
                    processedIds.add(uniqueId)

                    // 如果是短链接，解析为真实链接类型
                    if (linkType == LinkType.ShortLink) {
                        val realLink = biliClient.redirect("https://b23.tv/$id")
                        if (realLink != null) {
                            logger.info("短链接 https://b23.tv/$id 解析为: $realLink")
                            matchingInternalRegular(realLink, subject)?.let { results.add(it) }
                        }
                    } else if (linkType == LinkType.Dynamic && isOpusMatch(matchResult)) {
                        val opusUrl = normalizeOpusUrl(matchResult.value)
                        val cvId = resolveOpusCvId(opusUrl)
                        if (cvId != null) {
                            val articleKey = "${LinkType.Article.name}:$cvId"
                            if (!processedIds.contains(articleKey)) {
                                processedIds.add(articleKey)
                                results.add(ResolvedLinkInfo(LinkType.Article, cvId, subject))
                            }
                        } else {
                            results.add(ResolvedLinkInfo(linkType, id, subject))
                        }
                    } else {
                        results.add(ResolvedLinkInfo(linkType, id, subject))
                    }
                }

                matchResult = matchResult.next()
            }
        }
    }

    return results
}

data class ResolvedLinkInfo(val type: LinkType, val id: String, val subject: String? = null) : ResolveLink {
    override suspend fun drawGeneral(): String? = type.drawGeneral(id, subject)
    override suspend fun getLink(): String = type.getLink(id)
}

suspend fun matchingInternalRegular(content: String, subject: String? = null): ResolvedLinkInfo? {
    var matchResult: MatchResult? = null
    var type: LinkType? = null

    // 定义优先级顺序：短链接优先，其他保持原顺序
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
        logger.warn("未匹配到链接! -> $content")
        return null
    }

    val id = matchResult.destructured.component1()

    // 如果是短链接，解析为真实链接类型
    if (type == LinkType.ShortLink) {
        val realLink = biliClient.redirect("https://b23.tv/$id")
        if (realLink != null) {
            logger.info("短链接 https://b23.tv/$id 解析为: $realLink")
            // 递归解析真实链接
            return matchingInternalRegular(realLink, subject)
        } else {
            logger.warn("短链接解析失败: https://b23.tv/$id")
            return null
        }
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
                    val colors = resolveColors(author.mid, subject)
                    val footer = buildFooter(author.name, author.mid, id, pubdate.formatRelativeTime, "视频")

                    SkiaManager.executeDrawing {
                        val authorImg = author.drawGeneral(this, pubdate.formatRelativeTime, VIDEO_LINK(id), colors.first())
                        val contentImg = videoData.drawGeneral(this, true)
                        val imgList = listOf(authorImg, contentImg)
                        val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
                        val img = makeCardBg(this, cimg.height, colors) { it.drawImage(cimg, 0f, 0f) }
                        cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
                    }
                }
            }
            Article -> {
                biliClient.getArticleDetail("cv$id")?.run {
                    val articleData = toDrawData()
                    val colors = resolveColors(author.mid, subject)
                    val footer = buildFooter(author.name, author.mid, id, time.formatRelativeTime, "专栏")

                    SkiaManager.executeDrawing {
                        val authorImg = author.drawGeneral(this, time.formatRelativeTime, ARTICLE_LINK(id), colors.first())
                        val contentImg = articleData.drawGeneral(this)
                        val imgList = listOf(authorImg, contentImg)
                        val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
                        val img = makeCardBg(this, cimg.height, colors) { it.drawImage(cimg, 0f, 0f) }
                        cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
                    }
                }
            }
            Dynamic -> {
                biliClient.getDynamicDetail(id)?.run {
                    val colors = resolveColors(modules.moduleAuthor.mid, subject)
                    SkiaManager.executeDrawing {
                        val dynamic = this@run.drawDynamic(this, colors.first())
                        val img = makeCardBg(this, dynamic.height, colors) {
                            it.drawImage(dynamic, 0f, 0f)
                        }
                        cacheImage(img, "$idStr.png", CacheType.DRAW_SEARCH)
                    }
                }
            }
            Live -> {
                val room = biliClient.getLiveDetail(id) ?: return null
                val author = biliClient.userInfo(room.uid)?.toDrawAuthorData() ?: return null
                val liveData = room.toDrawData()
                val colors = resolveColors(room.uid, subject)
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
                    val authorImg = author.drawGeneral(this, author.sign ?: "", LIVE_LINK(id), colors.first())
                    val contentImg = liveData.drawGeneral(this)
                    val imgList = listOf(authorImg, contentImg)
                    val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
                    val img = makeCardBg(this, cimg.height, colors) { it.drawImage(cimg, 0f, 0f) }
                    cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
                }
            }
            User -> {
                val author = biliClient.userInfo(id.toLong())?.toDrawAuthorData() ?: return null
                val colors = resolveColors(author.mid, subject)
                val footer = buildFooter(author.name, author.mid, id, author.sign ?: "", "用户")

                SkiaManager.executeDrawing {
                    val authorImg = author.drawGeneral(this, author.sign ?: "", SPACE_LINK(id), colors.first())
                    val imgList = listOf(authorImg)
                    val cimg = imgList.assembleCard(this, id, footer, tag = "搜索", closeInputImages = true)
                    val img = makeCardBg(this, cimg.height, colors) { it.drawImage(cimg, 0f, 0f) }
                    cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
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

private fun resolveColors(uid: Long, subject: String?): List<Int> {
    return DynamicService.resolveColor(uid, subject)
        .split(";", "；").map { Color.makeRGB(it.trim()) }
}

private fun getDefaultColors(): List<Int> {
    return BiliConfigManager.config.imageConfig.defaultColor
        .split(";", "；").map { Color.makeRGB(it.trim()) }
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
