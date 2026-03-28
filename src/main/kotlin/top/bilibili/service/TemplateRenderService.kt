package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.connector.OutgoingPart
import top.bilibili.core.BiliBiliBot
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.utils.ImageCache
import top.bilibili.utils.normalizeContactSubject

/**
 * 将模板和业务消息拼装成最终消息段，避免推送任务重复实现模板渲染细节。
 */
object TemplateRenderService {
    /**
     * 基于消息类型和会话作用域构建最终消息段，并在模板绘图失效时补文本兜底。
     */
    suspend fun buildSegments(
        message: BiliMessage,
        contactStr: String,
        overrideTemplate: String? = null,
    ): List<OutgoingPart> {
        BiliBiliBot.logger.info("开始构建消息段...")
        val segments = mutableListOf<OutgoingPart>()
        val config = runCatching { BiliConfigManager.config }.getOrElse { BiliConfig() }

        return try {
            val template = overrideTemplate ?: when (message) {
                is DynamicMessage -> resolveTemplate(config, contactStr, message.mid, "dynamic")
                is LiveMessage -> resolveTemplate(config, contactStr, message.mid, "live")
                is LiveCloseMessage -> resolveTemplate(config, contactStr, message.mid, "liveClose")
            }

            val messageParts = template.split("\r")
            for ((index, part) in messageParts.withIndex()) {
                if (index > 0) {
                    segments.add(OutgoingPart.text("\n"))
                }

                val content = replacePlaceholders(part, message)
                parseContent(content, message, segments, config)
            }

            if (template.contains("{draw}") && !hasMeaningfulSegments(segments)) {
                buildDrawDisabledFallback(message)?.let { fallback ->
                    segments.add(OutgoingPart.text(fallback))
                }
            }

            segments
        } catch (e: Exception) {
            BiliBiliBot.logger.error("构建消息段失败: ${e.message}", e)
            listOf(OutgoingPart.text("${message.name} 发布了新内容"))
        }
    }

    private fun hasMeaningfulSegments(segments: List<OutgoingPart>): Boolean {
        return segments.any { segment ->
            segment.type == "image" || (segment.type == "text" && !segment.data["text"].orEmpty().isBlank())
        }
    }

    private fun resolveTemplate(config: BiliConfig, contactStr: String, mid: Long, type: String): String {
        val data = BiliData
        val normalizedContact = normalizeContactSubject(contactStr) ?: contactStr

        val templateMap = when (type) {
            "dynamic" -> data.dynamicPushTemplate
            "live" -> data.livePushTemplate
            "liveClose" -> data.liveCloseTemplate
            else -> return ""
        }

        val uidTemplate = when (type) {
            "dynamic" -> data.dynamicPushTemplateByUid[normalizedContact]?.get(mid)
            "live" -> data.livePushTemplateByUid[normalizedContact]?.get(mid)
            "liveClose" -> data.liveCloseTemplateByUid[normalizedContact]?.get(mid)
            else -> null
        }

        val customTemplate = templateMap.entries.find { (_, contacts) ->
            normalizedContact in contacts
        }?.key

        val templateName = uidTemplate ?: customTemplate ?: when (type) {
            "dynamic" -> config.templateConfig.defaultDynamicPush
            "live" -> config.templateConfig.defaultLivePush
            "liveClose" -> config.templateConfig.defaultLiveClose
            else -> "OneMsg"
        }

        return when (type) {
            "dynamic" -> config.templateConfig.dynamicPush[templateName] ?: "{draw}\n{name}@{type}\n{link}"
            "live" -> config.templateConfig.livePush[templateName] ?: "{draw}\n{name}@直播\n{link}"
            "liveClose" -> config.templateConfig.liveClose[templateName] ?: "{name} 直播结束啦!\n直播时长: {duration}"
            else -> ""
        }
    }

    private fun replacePlaceholders(template: String, message: BiliMessage): String {
        var result = template

        result = result.replace("{name}", message.name)
        result = result.replace("{uid}", message.mid.toString())
        result = result.replace("{mid}", message.mid.toString())
        result = result.replace("{time}", message.time)

        when (message) {
            is DynamicMessage -> {
                result = result.replace("{type}", message.type.text)
                result = result.replace("{did}", message.did)
                result = result.replace("{content}", message.content)
                result = result.replace("{link}", "https://t.bilibili.com/${message.did}")
                val linksText = message.links?.joinToString("\n") { "${it.tag}: ${it.value}" } ?: ""
                result = result.replace("{links}", linksText)
            }

            is LiveMessage -> {
                result = result.replace("{title}", message.title)
                result = result.replace("{area}", message.area)
                result = result.replace("{link}", message.link)
                result = result.replace("{cover}", message.cover)
            }

            is LiveCloseMessage -> {
                result = result.replace("{title}", message.title)
                result = result.replace("{duration}", message.duration)
                result = result.replace("{area}", message.area)
                result = result.replace("{link}", message.link)
            }
        }

        return result
    }

    private suspend fun parseContent(
        content: String,
        message: BiliMessage,
        segments: MutableList<OutgoingPart>,
        config: BiliConfig,
    ) {
        var currentText = content

        if (currentText.contains("{draw}")) {
            val drawPath = message.drawPath
            if (drawPath != null && FeatureSwitchService.canRenderPushDraw(config)) {
                segments.add(OutgoingPart.image(drawPath))
            }
            currentText = currentText.replace("{draw}", "")
        }

        if (currentText.contains("{images}")) {
            if (message is DynamicMessage && !message.images.isNullOrEmpty()) {
                currentText = currentText.replace("{images}", "")
                for (imageUrl in message.images) {
                    val cachedUrl = ImageCache.cacheImage(imageUrl)
                    if (cachedUrl != null) {
                        segments.add(OutgoingPart.image(cachedUrl))
                    }
                }
            } else {
                currentText = currentText.replace("{images}", "")
            }
        }

        if (currentText.isNotBlank()) {
            segments.add(OutgoingPart.text(currentText.trim()))
        }
    }

    private fun buildDrawDisabledFallback(message: BiliMessage): String? {
        return when (message) {
            is DynamicMessage -> "${message.name}@${message.type.text}\nhttps://t.bilibili.com/${message.did}"
            is LiveMessage -> "${message.name}@直播\n${message.link}"
            is LiveCloseMessage -> null
        }
    }
}
