package top.bilibili.api

import io.ktor.client.request.*
import top.bilibili.core.BiliBiliBot
import top.bilibili.client.BiliClient
import top.bilibili.data.*
import top.bilibili.service.pgcRegex
import top.bilibili.utils.bodyParameter
import top.bilibili.utils.decode


internal suspend inline fun <reified T> BiliClient.pgcGet(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T? = get<PgcResult>(url, block).result?.decode()


suspend fun BiliClient.followPgc(ssid: Long): PgcFollow? {
    return post<PgcResult>(FOLLOW_PGC) {
        bodyParameter("season_id", ssid)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }.result?.decode()
}

suspend fun BiliClient.unFollowPgc(ssid: Long): PgcFollow? {
    return post<PgcResult>(UNFOLLOW_PGC) {
        bodyParameter("season_id", ssid)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }.result?.decode()
}

suspend fun BiliClient.getPcgInfo(id: String): BiliDetail? {
    val regex = pgcRegex.find(id) ?: return null

    val type = regex.destructured.component1()
    val id = regex.destructured.component2().toLong()

    return when (type) {
        "ss" -> getSeasonInfo(id)
        "md" -> {
            // 先获取 media 信息，然后用 season_id 获取完整的 season 信息（包含简介）
            val mediaInfo = getMediaInfo(id)
            if (mediaInfo != null) {
                getSeasonInfo(mediaInfo.media.seasonId)
            } else {
                null
            }
        }
        "ep" -> getEpisodeInfo(id)
        else -> null
    }
}

suspend fun BiliClient.getMediaInfo(mdid: Long): PgcMedia? {
    return pgcGet(PGC_MEDIA_INFO) {
        parameter("media_id", mdid)
    }
}

suspend fun BiliClient.getEpisodeInfo(epid: Long): PgcSeason? {
    return pgcGet(PGC_INFO) {
        parameter("ep_id", epid)
    }
}

suspend fun BiliClient.getSeasonInfo(ssid: Long): PgcSeason? {
    return pgcGet(PGC_INFO) {
        parameter("season_id", ssid)
    }
}