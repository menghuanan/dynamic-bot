package top.bilibili.service

import top.bilibili.connector.onebot11.vendors.napcat.MessageSegment

/**
 * 将平台原始消息与消息段收敛成可读日志文本，避免日志中充斥长 CQ 片段。
 */
object MessageLogSimplifier {
    private const val MAX_INPUT_LENGTH = 10_000
    private const val MAX_OUTPUT_LENGTH = 100
    private val cqPattern = "\\[CQ:([^,\\]]+)(?:,([^\\]]*))?\\]".toRegex()

    /**
     * 简化原始 OneBot 文本消息，并在输入过长时先截断保护日志体积。
     */
    fun simplifyIncomingRaw(rawMessage: String, onTooLong: (Int) -> Unit): String {
        val safeMessage = if (rawMessage.length > MAX_INPUT_LENGTH) {
            onTooLong(rawMessage.length)
            rawMessage.take(MAX_INPUT_LENGTH)
        } else {
            rawMessage
        }

        if (!safeMessage.contains("[CQ:")) {
            return truncate(safeMessage)
        }

        val result = StringBuilder()
        var lastIndex = 0
        cqPattern.findAll(safeMessage).forEach { match ->
            if (match.range.first > lastIndex) {
                result.append(safeMessage.substring(lastIndex, match.range.first))
            }
            val params = parseParams(match.groups[2]?.value.orEmpty())
            result.append(placeholderForType(match.groupValues[1], params))
            lastIndex = match.range.last + 1
        }

        if (lastIndex < safeMessage.length) {
            result.append(safeMessage.substring(lastIndex))
        }

        return truncate(result.toString())
    }

    /**
     * 将结构化消息段渲染成紧凑日志文本，方便统一记录多种消息类型。
     */
    fun simplifySegments(segments: List<MessageSegment>): String {
        val rendered = buildString {
            segments.forEach { segment ->
                if (segment.type == "text") {
                    append(segment.data["text"].orEmpty())
                } else {
                    append(placeholderForType(segment.type, segment.data))
                }
            }
        }
        return truncate(rendered)
    }

    internal fun placeholderForType(type: String, data: Map<String, String> = emptyMap()): String = when (type) {
        "image" -> "[\u56fe\u7247]"
        "face" -> "[\u8868\u60c5]"
        "at" -> if (data["qq"] == "all") "[@\u5168\u4f53]" else "[@]"
        "reply" -> "[\u56de\u590d]"
        "video" -> "[\u89c6\u9891]"
        "record" -> "[\u8bed\u97f3]"
        "file" -> "[\u6587\u4ef6]"
        "json" -> "[JSON\u6d88\u606f]"
        "xml" -> "[XML\u6d88\u606f]"
        "rps" -> "[\u731c\u62f3]"
        "dice" -> "[\u9ab0\u5b50]"
        "shake" -> "[\u6296\u52a8]"
        "poke" -> "[\u6233\u4e00\u6233]"
        "share" -> "[\u5206\u4eab]"
        "contact" -> "[\u8054\u7cfb\u4eba]"
        "location" -> "[\u4f4d\u7f6e]"
        "music" -> "[\u97f3\u4e50]"
        "forward" -> "[\u5408\u5e76\u8f6c\u53d1]"
        "node" -> "[\u8f6c\u53d1\u8282\u70b9]"
        "markdown" -> "[Markdown]"
        "lightapp" -> "[\u8f7b\u5e94\u7528]"
        "mface" -> "[\u5927\u8868\u60c5]"
        else -> "[\u672a\u77e5\u7c7b\u578b:$type]"
    }

    private fun parseParams(rawParams: String): Map<String, String> {
        if (rawParams.isBlank()) {
            return emptyMap()
        }
        return rawParams.split(',')
            .mapNotNull { entry ->
                val index = entry.indexOf('=')
                if (index <= 0) {
                    null
                } else {
                    entry.substring(0, index) to entry.substring(index + 1)
                }
            }
            .toMap()
    }

    private fun truncate(text: String): String {
        return if (text.length > MAX_OUTPUT_LENGTH) {
            text.take(MAX_OUTPUT_LENGTH) + "..."
        } else {
            text
        }
    }
}
