package top.bilibili.service

/**
 * 为消息日志提供稳定的简化入口，避免调用方直接依赖底层简化细节。
 */
object MessageLogFormatter {
    /**
     * 将原始消息压缩成适合日志展示的短文本，减少长消息刷屏风险。
     */
    fun simplify(rawMessage: String, onTooLong: (Int) -> Unit): String {
        return MessageLogSimplifier.simplifyIncomingRaw(rawMessage, onTooLong)
    }
}
