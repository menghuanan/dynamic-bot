package top.bilibili.service

object MessageLogFormatter {
    fun simplify(rawMessage: String, onTooLong: (Int) -> Unit): String {
        return MessageLogSimplifier.simplifyIncomingRaw(rawMessage, onTooLong)
    }
}
