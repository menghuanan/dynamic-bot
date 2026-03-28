package top.bilibili.api

import io.ktor.client.request.*
import top.bilibili.client.ApiRequestTrace
import top.bilibili.client.BiliClient
import top.bilibili.data.*

/**
 * 获取账号全部最新动态
 * @param page 分页 (每页20左右)
 * @param type 动态类型 video: 视频  pgc: 番剧  article: 专栏
 */
suspend fun BiliClient.getNewDynamic(
    page: Int = 1,
    type: String = "all",
    source: String = "unknown"
): DynamicList? {
    return getData(
        NEW_DYNAMIC,
        trace = ApiRequestTrace(source = source, api = "NEW_DYNAMIC", url = NEW_DYNAMIC)
    ) {
        // 显式固定接口时区，避免服务端按运行环境时区返回不稳定的分页结果。
        parameter("timezone_offset", "-480")
        parameter("type", type)
        parameter("page", page)
        // 强制使用统一卡片结构，避免不同动态样式导致字段解析不一致。
        parameter("features", "itemOpusStyle")
    }
}

/**
 * 获取用户最新动态
 * @param uid 用户ID
 * @param hasTop 是否包含置顶动态
 * @param offset 动态偏移
 */
suspend fun BiliClient.getUserNewDynamic(uid: Long, hasTop: Boolean = false, offset: String = ""): DynamicList? {
    return getData(if (hasTop) SPACE_DYNAMIC else NEW_DYNAMIC) {
        // 显式固定接口时区，避免服务端按运行环境时区返回不稳定的分页结果。
        parameter("timezone_offset", "-480")
        parameter("host_mid", uid)
        parameter("offset", offset)
        // 强制使用统一卡片结构，避免不同动态样式导致字段解析不一致。
        parameter("features", "itemOpusStyle")
    }
}

/**
 * 获取指定动态详情
 * @param did 动态ID
 */
suspend fun BiliClient.getDynamicDetail(did: String): DynamicItem? {
    return getData<DynamicDetail>(DYNAMIC_DETAIL) {
        // 详情接口同样依赖时区参数，保持与列表接口返回口径一致。
        parameter("timezone_offset", "-480")
        parameter("id", did)
        // 统一详情卡片结构，减少后续字段兼容分支。
        parameter("features", "itemOpusStyle")
    }?.item
}

/**
 * 获取视频详情。
 *
 * @param id 视频标识，支持 `BV` 号或 `av` 号
 */
suspend fun BiliClient.getVideoDetail(id: String): VideoDetail? {
    return getData(VIDEO_DETAIL) {
        if (id.contains("BV")) parameter("bvid", id)
        else parameter("aid", id.removePrefix("av"))
    }
}

/**
 * 通过旧版专栏详情接口获取专栏信息。
 *
 * @param id 专栏标识，支持带 `cv` 前缀或纯数字 ID
 */
suspend fun BiliClient.getArticleDetailOld(id: String): ArticleDetail? {
    return getData(ARTICLE_DETAIL) {
        if (id.startsWith("cv")) parameter("id", id.removePrefix("cv"))
        else parameter("id", id)
    }
}

/**
 * 获取专栏视图信息。
 *
 * @param id 专栏标识，支持带 `cv` 前缀或纯数字 ID
 */
suspend fun BiliClient.getArticleView(id: String): ArticleViewInfo? {
    return getData(ARTICLE_VIEW) {
        if (id.startsWith("cv")) parameter("id", id.removePrefix("cv"))
        else parameter("id", id)
    }
}

/**
 * 获取单个专栏详情。
 *
 * @param id 专栏标识
 */
suspend fun BiliClient.getArticleDetail(id: String): ArticleDetail? {
    return getArticleList(listOf(id))?.get(id)
}

/**
 * 批量获取专栏详情。
 *
 * @param ids 专栏标识列表
 */
suspend fun BiliClient.getArticleList(ids: List<String>): Map<String, ArticleDetail>? {
    return getData(ARTICLE_LIST) {
        parameter("ids", ids.joinToString(","))
    }
}
