package top.bilibili.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 登录轮询接口响应模型。
 */
@Serializable
data class LoginData(
    @SerialName("code")
    val code: Int? = null,
    @SerialName("message")
    val message: String? = null,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("timestamp")
    val timestamp: Long? = null,
    @SerialName("url")
    val url: String? = null,
)

/**
 * 登录二维码信息模型。
 */
@Serializable
data class LoginQrcode(
    @SerialName("url")
    val url: String,
    @SerialName("qrcode_key")
    val qrcodeKey: String? = null
)
