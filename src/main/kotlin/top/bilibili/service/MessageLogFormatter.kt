package top.bilibili.service

object MessageLogFormatter {
    fun simplify(rawMessage: String, onTooLong: (Int) -> Unit): String {
        if (!rawMessage.contains("[CQ:")) {
            return if (rawMessage.length > 100) {
                rawMessage.take(100) + "..."
            } else {
                rawMessage
            }
        }

        val result = StringBuilder()
        val safeMessage = if (rawMessage.length > 10000) {
            onTooLong(rawMessage.length)
            rawMessage.take(10000)
        } else {
            rawMessage
        }

        val cqPattern = """\[CQ:([^,\]]+)(?:,[^\]]+)?\]""".toRegex()
        var lastIndex = 0

        cqPattern.findAll(safeMessage).forEach { match ->
            if (match.range.first > lastIndex) {
                result.append(safeMessage.substring(lastIndex, match.range.first))
            }

            when (val cqType = match.groupValues[1]) {
                "image" -> result.append("[图片]")
                "face" -> result.append("[表情]")
                "at" -> result.append("[提及]")
                "reply" -> result.append("[回复]")
                "video" -> result.append("[视频]")
                "record" -> result.append("[语音]")
                "file" -> result.append("[文件]")
                "json" -> result.append("[JSON消息]")
                "xml" -> result.append("[XML消息]")
                else -> result.append("[$cqType]")
            }

            lastIndex = match.range.last + 1
        }

        if (lastIndex < safeMessage.length) {
            result.append(safeMessage.substring(lastIndex))
        }

        val simplified = result.toString()
        return if (simplified.length > 100) {
            simplified.take(100) + "..."
        } else {
            simplified
        }
    }
}
