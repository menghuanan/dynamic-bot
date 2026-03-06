package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.core.BiliBiliBot
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.napcat.MessageSegment
import top.bilibili.utils.ImageCache

object TemplateRenderService {
    suspend fun buildSegments(
        message: BiliMessage,
        contactStr: String,
        overrideTemplate: String? = null
    ): List<MessageSegment> {
        BiliBiliBot.logger.info("开始构建消息段...")
        val segments = mutableListOf<MessageSegment>()

        return try {
            BiliBiliBot.logger.info("获取推送模板...")
            val template = overrideTemplate ?: when (message) {
                is DynamicMessage -> resolveTemplate(contactStr, message.mid, "dynamic")
                is LiveMessage -> resolveTemplate(contactStr, message.mid, "live")
                is LiveCloseMessage -> resolveTemplate(contactStr, message.mid, "liveClose")
            }
            BiliBiliBot.logger.info("使用模板: $template")

            val messageParts = template.split("\r")
            BiliBiliBot.logger.info("模板拆分为 ${messageParts.size} 部分")

            for ((index, part) in messageParts.withIndex()) {
                if (index > 0) {
                    segments.add(MessageSegment.text("\n"))
                }

                BiliBiliBot.logger.info("替换模板变量...")
                val content = replacePlaceholders(part, message)
                BiliBiliBot.logger.info("替换后内容: ${content.take(100)}...")

                BiliBiliBot.logger.info("解析内容并提取图片和文本...")
                parseContent(content, message, segments)
                BiliBiliBot.logger.info("当前消息段数量: ${segments.size}")
            }

            BiliBiliBot.logger.info("消息段构建完成，共 ${segments.size} 个")
            segments
        } catch (e: Exception) {
            BiliBiliBot.logger.error("构建消息段失败: ${e.message}", e)
            listOf(MessageSegment.text("${message.name} 发布了新内容"))
        }
    }

    private fun resolveTemplate(contactStr: String, mid: Long, type: String): String {
        val config = runCatching { BiliConfigManager.config }.getOrElse { BiliConfig() }
        val data = BiliData

        val templateMap = when (type) {
            "dynamic" -> data.dynamicPushTemplate
            "live" -> data.livePushTemplate
            "liveClose" -> data.liveCloseTemplate
            else -> return ""
        }

        val uidTemplate = when (type) {
            "dynamic" -> data.dynamicPushTemplateByUid[contactStr]?.get(mid)
            "live" -> data.livePushTemplateByUid[contactStr]?.get(mid)
            "liveClose" -> data.liveCloseTemplateByUid[contactStr]?.get(mid)
            else -> null
        }

        val customTemplate = templateMap.entries.find { (_, contacts) ->
            contactStr in contacts
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
            "liveClose" -> config.templateConfig.liveClose[templateName] ?: "{name} 直播结束啦\n直播时长: {duration}"
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
        segments: MutableList<MessageSegment>
    ) {
        var currentText = content

        if (currentText.contains("{draw}")) {
            val drawPath = message.drawPath
            if (drawPath != null && BiliConfigManager.config.enableConfig.drawEnable) {
                val imageUrl = ImageCache.toFileUrl(drawPath)
                segments.add(MessageSegment.image(imageUrl))
                currentText = currentText.replace("{draw}", "")
            } else {
                currentText = currentText.replace("{draw}", "")
            }
        }

        if (currentText.contains("{images}")) {
            if (message is DynamicMessage && !message.images.isNullOrEmpty()) {
                currentText = currentText.replace("{images}", "")
                for (imageUrl in message.images) {
                    val cachedUrl = ImageCache.cacheImage(imageUrl)
                    if (cachedUrl != null) {
                        segments.add(MessageSegment.image(cachedUrl))
                    }
                }
            } else {
                currentText = currentText.replace("{images}", "")
            }
        }

        if (currentText.isNotBlank()) {
            segments.add(MessageSegment.text(currentText.trim()))
        }
    }
}
