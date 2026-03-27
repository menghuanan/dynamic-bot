package top.bilibili.connector

import kotlin.math.pow

class ConnectionBackoffPolicy(
    private val baseDelayMillis: Long,
    private val maxDelayMillis: Long,
    private val multiplier: Double = 2.0,
) {
    /**
     * 返回第 N 次重连应使用的退避时长，并对指数增长结果做上限约束。
     */
    fun nextDelayMillis(attempt: Int): Long {
        if (attempt <= 0) {
            return 0L
        }
        val factor = multiplier.pow((attempt - 1).toDouble())
        val computedDelay = (baseDelayMillis * factor).toLong()
        return computedDelay.coerceAtMost(maxDelayMillis)
    }
}
