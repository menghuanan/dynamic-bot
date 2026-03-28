package top.bilibili.api

import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.http.*
import top.bilibili.client.ApiRequestTrace
import top.bilibili.client.BiliClient
import top.bilibili.data.BiliResult
import top.bilibili.data.ShortLinkData
import top.bilibili.data.WbiImg
import top.bilibili.utils.*
import java.time.LocalDate

/**
 * 生成 Twemoji CDN 图片地址。
 *
 * @param code emoji 资源编码
 */
fun twemoji(code: String) = "$TWEMOJI/$code.png"

private var isLogin = true

/**
 * 请求通用数据接口，并将响应解码为指定类型。
 *
 * @param url 接口地址
 * @param trace 请求追踪信息
 * @param block 请求参数构建逻辑
 */
internal suspend inline fun <reified T> BiliClient.getData(
    url: String,
    trace: ApiRequestTrace = ApiRequestTrace(source = "unknown", api = url, url = url),
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T? {
    val res = get<BiliResult>(url, trace, block)

    return if (res.code == -101) {
        // 只在首次检测到登录失效时提醒，避免后续每次请求都重复刷屏。
        if (isLogin) actionNotify("账号登录失效，请使用 /login 重新登录")
        isLogin = false
        throw Exception("账号登录失效，请使用 /login 重新登录")
    } else if (res.code != 0 || res.data == null) {
        throw Exception("URL: $url, CODE: ${res.code}, MSG: ${res.message}")
    } else {
        isLogin = true
        res.data.decode()
    }
}

/**
 * 请求带 WBI 签名的数据接口，并将响应解码为指定类型。
 *
 * @param url 接口地址
 * @param trace 请求追踪信息
 * @param block 请求参数构建逻辑
 */
internal suspend inline fun <reified T> BiliClient.getDataWithWbi(
    url: String,
    trace: ApiRequestTrace = ApiRequestTrace(source = "unknown", api = url, url = url),
    crossinline block: HttpRequestBuilder.() -> Unit = {}
): T? {
    val builder = HttpRequestBuilder()
    builder.block()
    val params = builder.url.parameters.build().formUrlEncode()
    val wts = System.currentTimeMillis() / 1000
    val wrid = "$params&wts=$wts${getVerifyString()}".md5()
    return getData(url, trace) {
        block()
        // 服务端会校验 WBI 签名，补齐签名参数后请求才会被接受。
        parameter("w_rid", wrid)
        parameter("wts", wts)
    }
}

/**
 * 获取重定向后的目标地址。
 *
 * @param url 原始地址
 */
suspend fun BiliClient.redirect(url: String): String? {
    return useHttpClient {
        it.config {
            followRedirects = false
            expectSuccess = false
        }.head(url)
    }.headers[HttpHeaders.Location]
}

/**
 * 生成视频短链。
 *
 * @param aid 视频 ID
 */
suspend fun BiliClient.videoShortLink(aid: String): String? =
    toShortLink(aid, "main.ugc-video-detail.0.0.pv", "vinfo_player")

/**
 * 生成专栏短链。
 *
 * @param aid 专栏 ID
 */
suspend fun BiliClient.articleShortLink(aid: String): String? =
    toShortLink(aid, "read.column-detail.roof.8.click")

/**
 * 生成动态短链。
 *
 * @param did 动态 ID
 */
suspend fun BiliClient.dynamicShortLink(did: String): String? =
    toShortLink(did, "dt.dt-detail.0.0.pv", "dynamic")

/**
 * 生成直播间短链。
 *
 * @param rid 直播间 ID
 */
suspend fun BiliClient.liveShortLink(rid: String): String? =
    toShortLink(rid, "live.live-room-detail.0.0.pv", "vertical-three-point-panel")

/**
 * 生成空间页短链。
 *
 * @param mid 用户 ID
 */
suspend fun BiliClient.spaceShortLink(mid: String): String? =
    toShortLink(mid, "dt.space-dt.0.0.pv")

/**
 * 调用短链接口生成分享地址。
 *
 * @param oid 业务对象 ID
 * @param shareId 分享场景 ID
 * @param shareOrigin 分享来源
 */
suspend fun BiliClient.toShortLink(oid: String, shareId: String, shareOrigin: String? = null): String? {
    return try {
        useHttpClient {
            it.post(SHORT_LINK) {
                bodyParameter("build", "6880300")
                bodyParameter("buvid", "abcdefg")
                bodyParameter("platform", "android")
                bodyParameter("oid", oid)
                bodyParameter("share_channel", "QQ")
                bodyParameter("share_id", shareId)
                bodyParameter("share_mode", "3")
                if (shareOrigin != null) bodyParameter("share_origin", shareOrigin)
            }.body<String>().decode<BiliResult>().data?.decode<ShortLinkData>()?.link
        }
    } catch (e: Exception) {
        // 短链只是附加能力，失败时返回空值可避免影响主流程。
        null
    }
}

var lastWbiTime: LocalDate = LocalDate.now()
var wbiImg: WbiImg? = null

/**
 * 获取当前用于 WBI 签名的图片信息。
 */
suspend fun getWbiImg(): WbiImg {
    val now = LocalDate.now()
    if (now.isAfter(lastWbiTime) || wbiImg == null) {
        // 按天缓存签名素材，避免每次请求都额外访问用户信息接口。
        lastWbiTime = now
        wbiImg = biliClient.userInfo()?.wbiImg
    }
    return wbiImg!!
}

/**
 * 生成 WBI 签名所需的校验字符串。
 */
suspend fun getVerifyString(): String {
    val wi = getWbiImg()
    val r = splitUrl(wi.imgUrl) + splitUrl(wi.subUrl)
    val array = intArrayOf(46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52)
    return buildString {
        array.forEach { t ->
            if (t < r.length) {
                append(r[t])
            }
        }
        // 按官方约定截断为 32 位，避免签名结果与服务端算法不一致。
    }.slice(IntRange(0, 31))
}

/**
 * 从资源地址中提取不带扩展名的文件名。
 *
 * @param url 资源地址
 */
fun splitUrl(url: String): String {
    return url.removeSuffix("/").split("/").last().split(".").first()
}
