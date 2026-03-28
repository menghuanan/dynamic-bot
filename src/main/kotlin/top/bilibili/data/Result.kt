package top.bilibili.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * 通用 B 站接口响应包装。
 */
@Serializable
data class BiliResult(
    @SerialName("code")
    val code: Int,
    @SerialName("message")
    val message: String? = null,
    @SerialName("ttl")
    val ttl: Int? = null,
    @SerialName("data")
    val data: JsonElement? = null
)

/**
 * PGC 接口响应包装。
 */
@Serializable
data class PgcResult(
    @SerialName("code")
    val code: Int,
    @SerialName("message")
    val message: String? = null,
    @SerialName("result")
    val result: JsonElement? = null
)

/**
 * 短链接解析结果模型。
 */
@Serializable
data class ShortLinkData(
    @SerialName("title")
    val title: String? = null,
    @SerialName("content")
    val content: String? = null,
    @SerialName("link")
    val link: String,
    @SerialName("count")
    val count: Int? = null
)
