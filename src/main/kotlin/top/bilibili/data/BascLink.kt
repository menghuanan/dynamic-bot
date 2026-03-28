package top.bilibili.data

import top.bilibili.BiliConfigManager
import top.bilibili.api.articleShortLink
import top.bilibili.api.dynamicShortLink
import top.bilibili.api.liveShortLink
import top.bilibili.api.spaceShortLink
import top.bilibili.utils.biliClient

/** B 站主站链接前缀。 */
const val BASE_LINK = "https://www.bilibili.com"
/** 动态链接前缀。 */
const val BASE_DYNAMIC = "https://t.bilibili.com"
/** 专栏链接前缀。 */
const val BASE_ARTICLE = "https://www.bilibili.com/read"
/** 视频链接前缀。 */
const val BASE_VIDEO = "https://www.bilibili.com/video"
/** 音频链接前缀。 */
const val BASE_MUSIC = "https://www.bilibili.com/audio"
/** 番剧播放页链接前缀。 */
const val BASE_PGC = "https://www.bilibili.com/bangumi/play"
/** 番剧媒体页链接前缀。 */
const val BASE_PGC_MEDIA = "https://www.bilibili.com/bangumi/media"
/** 直播链接前缀。 */
const val BASE_LIVE = "https://live.bilibili.com"
/** 空间主页链接前缀。 */
const val BASE_SPACE = "https://space.bilibili.com"
/** 短链前缀。 */
const val BASE_SHORT = "https://b23.tv"

/**
 * 是否优先生成短链接。
 */
val toShortLink: Boolean by lazy { BiliConfigManager.config.pushConfig.toShortLink }

/**
 * 生成动态链接，必要时优先使用短链。
 */
suspend fun DYNAMIC_LINK(id: String) =
    // 短链生成失败时回退长链，保证推送内容始终可点击。
    if (toShortLink) biliClient.dynamicShortLink(id).run {
        this?.removePrefix("https://") ?: "$BASE_DYNAMIC/$id"
    } else "$BASE_DYNAMIC/$id"

/**
 * 生成 Opus 动态链接，必要时优先使用短链。
 */
suspend fun OPUS_LINK(id: String) =
    if (toShortLink) biliClient.dynamicShortLink(id).run {
        this?.removePrefix("https://") ?: "$BASE_LINK/opus/$id"
    } else "$BASE_LINK/opus/$id"

/**
 * 生成专栏链接，必要时优先使用短链。
 */
suspend fun ARTICLE_LINK(id: String) =
    if (toShortLink) {
        biliClient.articleShortLink(id).run {
            this?.removePrefix("https://") ?: "$BASE_ARTICLE/cv$id"
        }
    }else "$BASE_ARTICLE/cv$id"

/**
 * 生成视频链接，并兼容传入纯数字 AV 号的场景。
 */
fun VIDEO_LINK(id: String): String {
    // 保留原始 BV/av 前缀可以避免把已经规范的 ID 再次拼接出错误地址。
    val tid = if (id.contains("BV") || id.contains("av")) id else "av$id"
    return if (toShortLink) "$BASE_SHORT/$tid" else "$BASE_VIDEO/$tid"
}

/**
 * 生成用户空间链接，必要时优先使用短链。
 */
suspend fun SPACE_LINK(id: String): String = if (toShortLink) {
    biliClient.spaceShortLink(id).run {
        this?.removePrefix("https://") ?: "$BASE_SPACE/$id"
    }
} else "$BASE_SPACE/$id"

/** 生成音频链接。 */
fun MUSIC_LINK(id: String) = "$BASE_MUSIC/au$id"
/** 生成番剧媒体页链接。 */
fun MEDIA_LINK(id: String) = "$BASE_PGC_MEDIA/md$id"
/** 生成番剧季度链接。 */
fun SEASON_LINK(id: String) = if (toShortLink) "$BASE_SHORT/ss$id" else "$BASE_PGC/ss$id"
/** 生成番剧剧集链接。 */
fun EPISODE_LINK(id: String) = if (toShortLink) "$BASE_SHORT/ep$id" else "$BASE_PGC/ep$id"
/** 生成番剧相关链接，并根据 ID 形态区分媒体页与播放页。 */
fun PGC_LINK(id: String) = if (toShortLink) "$BASE_SHORT/$id" else if (id.startsWith("md")) "$BASE_PGC_MEDIA/$id" else "$BASE_PGC/$id"
/** 生成直播间链接，必要时优先使用短链。 */
suspend fun LIVE_LINK(id: String) = if (toShortLink) {
    biliClient.liveShortLink(id).run {
        this?.removePrefix("https://") ?: "$BASE_LIVE/$id"
    }
}else "$BASE_LIVE/$id"

