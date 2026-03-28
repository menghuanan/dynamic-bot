package top.bilibili.api

import io.ktor.client.request.*
import top.bilibili.core.BiliBiliBot
import top.bilibili.client.BiliClient
import top.bilibili.data.*
import top.bilibili.service.pgcRegex
import top.bilibili.utils.bodyParameter
import top.bilibili.utils.decode

/**
 * 请求番剧接口并将结果解码为指定类型。
 *
 * @param url 接口地址
 * @param block 请求参数构建逻辑
 */
internal suspend inline fun <reified T> BiliClient.pgcGet(
    url: String,
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T? = get<PgcResult>(url, block = block).result?.decode()

/**
 * 关注番剧。
 *
 * @param ssid 番剧 season ID
 */
suspend fun BiliClient.followPgc(ssid: Long): PgcFollow? {
    return post<PgcResult>(FOLLOW_PGC) {
        bodyParameter("season_id", ssid)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }.result?.decode()
}

/**
 * 取消关注番剧。
 *
 * @param ssid 番剧 season ID
 */
suspend fun BiliClient.unFollowPgc(ssid: Long): PgcFollow? {
    return post<PgcResult>(UNFOLLOW_PGC) {
        bodyParameter("season_id", ssid)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }.result?.decode()
}

/**
 * 根据 `ss`、`md`、`ep` 标识获取番剧详情。
 *
 * @param id 番剧相关标识字符串
 */
suspend fun BiliClient.getPcgInfo(id: String): BiliDetail? {
    val regex = pgcRegex.find(id) ?: return null

    val type = regex.destructured.component1()
    val parsedId = regex.destructured.component2().toLong()

    return when (type) {
        "ss" -> getSeasonInfo(parsedId)
        "md" -> {
            // 先获取 media 信息，然后用 season_id 获取完整的 season 信息（包含简介）
            val mediaInfo = getMediaInfo(parsedId)
            if (mediaInfo != null) {
                getSeasonInfo(mediaInfo.media.seasonId)
            } else {
                null
            }
        }
        "ep" -> getEpisodeInfo(parsedId)
        else -> null
    }
}

/**
 * 获取媒体维度的番剧信息。
 *
 * @param mdid 媒体 ID
 */
suspend fun BiliClient.getMediaInfo(mdid: Long): PgcMedia? {
    return pgcGet(PGC_MEDIA_INFO) {
        parameter("media_id", mdid)
    }
}

/**
 * 获取单集维度的番剧信息。
 *
 * @param epid 单集 ID
 */
suspend fun BiliClient.getEpisodeInfo(epid: Long): PgcSeason? {
    return pgcGet(PGC_INFO) {
        parameter("ep_id", epid)
    }
}

/**
 * 获取季度维度的番剧信息。
 *
 * @param ssid 季度 ID
 */
suspend fun BiliClient.getSeasonInfo(ssid: Long): PgcSeason? {
    return pgcGet(PGC_INFO) {
        parameter("season_id", ssid)
    }
}
