package top.bilibili.service

import org.jetbrains.skia.Color
import top.bilibili.BiliConfigManager
import top.bilibili.api.*
import top.bilibili.data.*
import top.bilibili.draw.*
import top.bilibili.utils.*
import java.time.Instant


private val regex = BiliConfigManager.config.linkResolveConfig.reg

suspend fun matchingRegular(content: String): ResolvedLinkInfo? {
    return if (regex.any { it.find(content) != null }) {
        logger.info("开始解析链接 -> $content")
        matchingInternalRegular(content)
    } else null
}

suspend fun matchingAllRegular(content: String): List<ResolvedLinkInfo> {
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
                            matchingInternalRegular(realLink)?.let { results.add(it) }
                        }
                    } else {
                        results.add(ResolvedLinkInfo(linkType, id))
                    }
                }

                matchResult = matchResult.next()
            }
        }
    }

    return results
}

data class ResolvedLinkInfo(val type: LinkType, val id: String) : ResolveLink {
    override suspend fun drawGeneral(): String? = type.drawGeneral(id)
    override suspend fun getLink(): String = type.getLink(id)
}

suspend fun matchingInternalRegular(content: String): ResolvedLinkInfo? {
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
            return matchingInternalRegular(realLink)
        } else {
            logger.warn("短链接解析失败: https://b23.tv/$id")
            return null
        }
    }

    return ResolvedLinkInfo(type, id)
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

    suspend fun drawGeneral(id: String): String? {
        return when (this) {
            VideoLink -> {
                biliClient.getVideoDetail(id)?.run {
                    drawGeneral(id, "视频", pubdate.formatTime, toDrawAuthorData(), toDrawData().drawGeneral(true))
                }
            }
            Article -> {
                biliClient.getArticleDetail("cv$id")?.run {
                    drawGeneral(id, "专栏", time.formatTime, author, toDrawData().drawGeneral())
                }
            }
            Dynamic -> {
                val color = Color.makeRGB(BiliConfigManager.config.imageConfig.defaultColor)
                biliClient.getDynamicDetail(id)?.run {
                    val dynamic = drawDynamic(color)
                    val img = makeCardBg(dynamic.height, listOf(color)) {
                        it.drawImage(dynamic, 0f, 0f)
                    }
                    cacheImage(img, "$idStr.png", CacheType.DRAW_SEARCH)
                }
            }
            Live -> {
                val room = biliClient.getLiveDetail(id) ?: return null
                val author = biliClient.userInfo(room.uid)?.toDrawAuthorData() ?: return null
                val data = room.toDrawData().drawGeneral()
                drawGeneral(id, "直播", Instant.now().epochSecond.formatTime, author, data)
            }
            User -> {
                val author = biliClient.userInfo(id.toLong())?.toDrawAuthorData() ?: return null
                drawGeneral(id, "用户", Instant.now().epochSecond.formatTime, author, null)
            }
            Pgc -> {
                val info = biliClient.getPcgInfo(id) ?: return null
                val author = info.toPgcAuthor() ?: return null
                val data = info.toPgc()?.drawSmall()
                val typeName = when (info) {
                    is PgcSeason -> when (info.type) {
                        1 -> "番剧"
                        2 -> "电影"
                        3 -> "纪录片"
                        4 -> "国创"
                        5 -> "电视剧"
                        7 -> "综艺"
                        else -> "番剧"
                    }
                    is PgcMedia -> info.media.typeName
                    else -> "番剧"
                }
                drawGeneral(id, typeName, Instant.now().epochSecond.formatTime, author, data)
            }
            ShortLink -> {
                val link = biliClient.redirect("https://b23.tv/$id")
                if (link != null) {
                    matchingInternalRegular(link)?.drawGeneral()
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

suspend fun drawGeneral(id: String, tag: String, time: String, author: ModuleAuthor, imgData: org.jetbrains.skia.Image?): String {
    val colors = BiliConfigManager.config.imageConfig.defaultColor.split(";", "；").map { Color.makeRGB(it.trim()) }

    // PGC 内容（番剧、电影等）不显示作者头像部分，且直接返回不加外框
    val isPgcContent = tag in listOf("番剧", "电影", "纪录片", "国创", "电视剧", "综艺")

    if (isPgcContent) {
        // PGC 内容直接返回，居中显示
        val img = makeCardBg(imgData!!.height, colors) {
            val x = (quality.imageWidth - imgData.width) / 2f
            it.drawImage(imgData, x, 0f)
        }
        return cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
    }

    // 非 PGC 内容保持原有逻辑
    val footer = buildFooter(author.name, author.mid, id, time, tag)
    val imgList = mutableListOf<org.jetbrains.skia.Image>()
    imgList.add(author.drawGeneral(time, VIDEO_LINK(id), colors.first()))
    imgData?.let { imgList.add(it) }

    val cimg = imgList.assembleCard(id, footer, tag = "搜索")

    val img = makeCardBg(cimg.height, colors) {
        it.drawImage(cimg, 0f, 0f)
    }
    return cacheImage(img, "$id.png", CacheType.DRAW_SEARCH)
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
        pendant = pendant
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

fun BiliDetail.toPgcAuthor(): ModuleAuthor? =
    when (this) {
        is PgcSeason -> {
            ModuleAuthor(
                "AUTHOR_TYPE_PGC",
                0,
                title,
                cover,
            )
        }
        is PgcMedia -> {
            ModuleAuthor(
                "AUTHOR_TYPE_PGC",
                0,
                media.title,
                media.cover,
            )
        }

        else -> null
    }
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
