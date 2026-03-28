package top.bilibili.utils.translate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 百度翻译接口的顶层响应结构。
 */
@Serializable
data class TransResult(
    @SerialName("from")
    val from: String? = null,
    @SerialName("to")
    val to: String? = null,
    @SerialName("trans_result")
    val transResult: List<TransData>? = null,
    @SerialName("error_code")
    val errorCode: String? = null,
    @SerialName("error_msg")
    val errorMsg: String? = null,
)

/**
 * 百度翻译接口中的单条翻译结果。
 */
@Serializable
data class TransData(
    @SerialName("src")
    val src: String,
    @SerialName("dst")
    val dst: String
)
