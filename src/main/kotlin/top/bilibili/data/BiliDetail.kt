package top.bilibili.data

import kotlinx.serialization.Serializable

/**
 * 各类 B 站详情数据的统一标记接口。
 */
@Serializable
sealed interface BiliDetail {
    //fun drawGeneral() {}
}
