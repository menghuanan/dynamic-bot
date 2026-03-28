package top.bilibili.api

import io.ktor.client.request.*
import top.bilibili.client.ApiRequestTrace
import top.bilibili.client.BiliClient
import top.bilibili.data.LiveInfo
import top.bilibili.data.LiveList
import top.bilibili.data.LiveRoomDetail
import top.bilibili.utils.decode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 获取关注直播列表。
 *
 * @param page 页码
 * @param pageSize 每页数量
 * @param source 请求来源标识
 */
suspend fun BiliClient.getLive(
    page: Int = 1,
    pageSize: Int = 20,
    source: String = "unknown"
): LiveList? {
    return getData(
        LIVE_LIST,
        trace = ApiRequestTrace(source = source, api = "LIVE_LIST", url = LIVE_LIST)
    ) {
        parameter("page", page)
        parameter("page_size", pageSize)
    }
}

/**
 * 批量获取用户直播状态。
 *
 * @param uids 用户 ID 列表
 * @param source 请求来源标识
 */
suspend fun BiliClient.getLiveStatus(
    uids: List<Long>,
    source: String = "unknown"
): Map<Long, LiveInfo>? {
    val raw = getData<JsonElement>(
        LIVE_STATUS_BATCH,
        trace = ApiRequestTrace(source = source, api = "LIVE_STATUS_BATCH", url = LIVE_STATUS_BATCH)
    ) {
        for (uid in uids) {
            parameter("uids[]", uid)
        }
    } ?: return null

    return when (raw) {
        is JsonObject -> raw.decode()
        // 接口无数据时会返回空数组，这里转为空映射以便调用方统一处理。
        is JsonArray -> if (raw.isEmpty()) emptyMap() else null
        else -> null
    }
}

/**
 * 获取直播间详情。
 *
 * @param rid 直播间 ID
 */
suspend fun BiliClient.getLiveDetail(rid: String): LiveRoomDetail? {
    return getData(LIVE_DETAIL) {
        parameter("room_id", rid)
    }
}
