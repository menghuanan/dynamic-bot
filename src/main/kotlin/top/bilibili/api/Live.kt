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
        is JsonArray -> if (raw.isEmpty()) emptyMap() else null
        else -> null
    }
}

suspend fun BiliClient.getLiveDetail(rid: String): LiveRoomDetail? {
    return getData(LIVE_DETAIL) {
        parameter("room_id", rid)
    }
}
