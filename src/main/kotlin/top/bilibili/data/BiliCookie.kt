package top.bilibili.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BiliCookie(
    @SerialName("SESSDATA")
    var sessData: String = "",
    @SerialName("bili_jct")
    var biliJct: String = ""
) {
    fun parse(cookie: String): BiliCookie {
        cookie.split("; ", ";").forEach {
            val cookieKV = it.split("=")
            if (cookieKV[0] == "SESSDATA") sessData = cookieKV[1].replace(",", "%2C").replace("*", "%2A")
            if (cookieKV[0] == "bili_jct") biliJct = cookieKV[1]
        }
        return this
    }

    fun isEmpty(): Boolean = sessData == "" && biliJct == ""

    /**
     * 脱敏显示 Cookie 信息（用于日志输出）
     * 安全修复：防止敏感信息在日志中完整泄露
     */
    override fun toString(): String {
        val maskedSessData = if (sessData.length > 8) {
            sessData.take(8) + "***"
        } else {
            "***"
        }
        val maskedBiliJct = if (biliJct.length > 4) {
            biliJct.take(4) + "***"
        } else {
            "***"
        }
        return "BiliCookie(SESSDATA=$maskedSessData, bili_jct=$maskedBiliJct)"
    }

    /**
     * 获取完整的 Cookie 字符串用于 HTTP 请求头
     * 仅在实际发送请求时调用此方法
     */
    fun toHeaderString(): String {
        return "SESSDATA=$sessData; bili_jct=$biliJct; "
    }
}

@Serializable
data class EditThisCookie(
    @SerialName("domain")
    val domain: String,
    @SerialName("expirationDate")
    val expirationDate: Double? = null,
    @SerialName("hostOnly")
    val hostOnly: Boolean = false,
    @SerialName("httpOnly")
    val httpOnly: Boolean,
    @SerialName("id")
    val id: Int,
    @SerialName("name")
    val name: String,
    @SerialName("path")
    val path: String,
    @SerialName("sameSite")
    val sameSite: String = "unspecified",
    @SerialName("secure")
    val secure: Boolean,
    @SerialName("session")
    val session: Boolean = false,
    @SerialName("storeId")
    val storeId: String = "0",
    @SerialName("value")
    val value: String
)

fun List<EditThisCookie>.toCookie(): BiliCookie {
    val bc = BiliCookie()
    for (cookie in this) {
        if (cookie.name == "SESSDATA") bc.sessData = cookie.value
        if (cookie.name == "bili_jct") bc.biliJct = cookie.value
    }
    return bc
}