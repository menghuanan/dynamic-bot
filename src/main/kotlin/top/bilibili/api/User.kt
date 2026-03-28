package top.bilibili.api

import io.ktor.client.request.*
import top.bilibili.core.BiliBiliBot
import top.bilibili.client.BiliClient
import top.bilibili.data.*
import top.bilibili.utils.bodyParameter
import top.bilibili.utils.decode

/**
 * 获取登录二维码。
 */
suspend fun BiliClient.getLoginQrcode(): LoginQrcode? = getData(LOGIN_QRCODE)

/**
 * 查询二维码登录状态。
 *
 * @param qrcodeKey 二维码 key
 */
suspend fun BiliClient.loginInfo(qrcodeKey: String): LoginData? {
    return getData(LOGIN_INFO) {
        parameter("qrcode_key", qrcodeKey)
    }
}

/**
 * 获取指定用户空间信息。
 *
 * @param uid 用户 ID
 */
suspend fun BiliClient.userInfo(uid: Long): BiliUser? {
    return getDataWithWbi(USER_INFO_WBI) {
        parameter("mid", uid)
    }
}

/**
 * 获取当前登录用户信息。
 */
suspend fun BiliClient.userInfo(): BiliUser? {
    return getData(USER_ID)
}

/**
 * 查询当前账号是否已关注指定用户。
 *
 * @param uid 用户 ID
 */
suspend fun BiliClient.isFollow(uid: Long): IsFollow? {
    return getData(IS_FOLLOW) {
        parameter("fid", uid)
    }
}

/**
 * 获取当前账号的关注分组列表。
 */
suspend fun BiliClient.followGroup(): List<FollowGroup>? {
    return getData(GROUP_LIST)
}

/**
 * 创建关注分组。
 *
 * @param tagName 分组名称
 */
suspend fun BiliClient.createGroup(tagName: String): FollowGroup? {
    return post<BiliResult>(CREATE_GROUP) {
        bodyParameter("tag", tagName)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }.data?.decode()
}

/**
 * 关注用户。
 *
 * @param uid 用户 ID
 */
suspend fun BiliClient.follow(uid: Long): BiliResult {
    return post(FOLLOW) {
        bodyParameter("fid", uid)
        bodyParameter("act", 1)
        bodyParameter("re_src", 11)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }
}

/**
 * 取消关注用户。
 *
 * @param uid 用户 ID
 */
suspend fun BiliClient.unfollow(uid: Long): BiliResult {
    return post(FOLLOW) {
        bodyParameter("fid", uid)
        bodyParameter("act", 2)
        bodyParameter("re_src", 11)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }
}

/**
 * 将用户加入指定关注分组。
 *
 * @param uid 用户 ID
 * @param tagid 分组 ID
 */
suspend fun BiliClient.groupAddUser(uid: Long, tagid: Int): BiliResult {
    return post(ADD_USER) {
        bodyParameter("fids", uid)
        bodyParameter("tagids", tagid)
        bodyParameter("csrf", BiliBiliBot.cookie.biliJct)
    }
}

/**
 * 搜索用户。
 *
 * @param keyword 搜索关键词
 * @param order 排序字段
 * @param orderSort 排序方向
 * @param userType 用户类型过滤
 * @param page 页码
 * @param pageSize 每页数量
 */
suspend fun BiliClient.searchUser(
    keyword: String,
    order: String = "",
    orderSort: Int = 0,
    userType: Int = 0,
    page: Int = 1,
    pageSize: Int = 20
): BiliSearch? {
    return getData(SEARCH) {
        parameter("page", page)
        parameter("page_size", pageSize)
        parameter("search_type", "bili_user") // bili_user  video  media_bangumi  media_ft  live  article  topic
        parameter("keyword", keyword)
        parameter("order", order) // 空  fans  level
        parameter("order_sort", orderSort) // 0: 由高到低  1: 由低到高
        parameter("user_type", userType) // 0: 全部用户  1: UP主用户  2: 普通用户  3: 认证用户
    }
}

/**
 * 获取用户投稿视频列表。
 *
 * @param uid 用户 ID
 * @param count 返回数量
 * @param order 排序方式
 */
suspend fun BiliClient.searchUserVideo(
    uid: Long,
    count: Int = 1,
    order: String = "pubdate",
): VideoList? {
    return getData(SPACE_SEARCH) {
        parameter("mid", uid)
        parameter("ps", count)
        parameter("order", order)
    }
}
