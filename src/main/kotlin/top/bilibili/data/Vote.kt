package top.bilibili.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 投票详情响应模型。
 */
@Serializable
data class Vote(
    @SerialName("info")
    val info: VoteInfo,
) {
    /**
     * 投票基础信息。
     */
    @Serializable
    data class VoteInfo(
        @SerialName("vote_id")
        val voteId: Long,
    )
}
