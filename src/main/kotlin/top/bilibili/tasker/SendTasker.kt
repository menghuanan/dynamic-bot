package top.bilibili.tasker

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.ContactId
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.napcat.MessageSegment
import top.bilibili.utils.ImageCache
import top.bilibili.utils.parseContactId

/**
 * 消息发送任务
 * 从 messageChannel 接收消息，转换为 OneBot v11 格式并通过 NapCat 发送
 */
object SendTasker : BiliTasker("SendTasker") {
    override var interval: Int = 1
    override val unitTime: Long = 500

    private val messageQueue = Channel<Pair<ContactId, List<MessageSegment>>>(100)

    override fun init() {
        BiliBiliBot.logger.info("SendTasker 已启动")

        // 启动消息队列处理协程
        BiliBiliBot.launch {
            processMessageQueue()
        }

        // 启动消息接收处理协程
        BiliBiliBot.launch {
            processMessages()
        }
    }

    override suspend fun main() {
        // 主循环在 init() 中启动的协程中运行
        delay(Long.MAX_VALUE)
    }

    /**
     * 处理消息发送队列
     */
    private suspend fun processMessageQueue() {
        for ((contact, segments) in messageQueue) {
            try {
                val success = BiliBiliBot.sendMessage(contact, segments)
                if (success) {
                    BiliBiliBot.logger.info("消息已发送到 ${contact.type}:${contact.id}")
                } else {
                    BiliBiliBot.logger.warn("消息发送失败 ${contact.type}:${contact.id}")
                }

                // 发送间隔
                delay(BiliConfigManager.config.pushConfig.pushInterval)
            } catch (e: Exception) {
                BiliBiliBot.logger.error("发送消息时出错: ${e.message}", e)
            }
        }
    }

    /**
     * 处理从 messageChannel 接收的消息
     */
    private suspend fun processMessages() {
        for (message in BiliBiliBot.messageChannel) {
            try {
                BiliBiliBot.logger.info("从 messageChannel 接收到消息: ${message.name} (${message.mid})")
                sendToSubscribers(message)
            } catch (e: Exception) {
                BiliBiliBot.logger.error("处理消息失败: ${e.message}", e)
            }
        }
    }

    /**
     * 发送消息到所有订阅者
     */
    private suspend fun sendToSubscribers(message: BiliMessage) {
        // 获取订阅数据
        val subData = BiliData.dynamic[message.mid]
        if (subData == null) {
            BiliBiliBot.logger.warn("用户 ${message.mid} 没有订阅数据，无法推送")
            return
        }

        BiliBiliBot.logger.info("准备推送到 ${subData.contacts.size} 个联系人")

        // 如果消息已指定联系人，只发送给该联系人
        val specificContact = message.contact
        if (specificContact != null) {
            BiliBiliBot.logger.info("消息指定了联系人: $specificContact")
            val contact = parseContactId(specificContact) ?: return
            val segments = buildMessageSegments(message, specificContact)
            messageQueue.send(contact to segments)
            return
        }

        // 发送给所有订阅该用户的联系人
        for (contactStr in subData.contacts) {
            try {
                BiliBiliBot.logger.info("处理联系人: $contactStr")
                val contact = parseContactId(contactStr) ?: continue

                // 检查是否被禁用
                if (subData.banList.containsKey(contactStr)) {
                    BiliBiliBot.logger.debug("联系人 $contactStr 已禁用推送")
                    continue
                }

                // 构建消息段
                val segments = buildMessageSegments(message, contactStr)
                BiliBiliBot.logger.info("为联系人 $contactStr 构建了 ${segments.size} 个消息段")

                // 加入发送队列
                messageQueue.send(contact to segments)
                BiliBiliBot.logger.info("消息已加入发送队列: ${contact.type}:${contact.id}")

                // 消息间隔
                delay(BiliConfigManager.config.pushConfig.messageInterval)

            } catch (e: Exception) {
                BiliBiliBot.logger.error("处理联系人 $contactStr 时出错: ${e.message}", e)
            }
        }
    }

    /**
     * 构建 OneBot v11 消息段
     */
    private suspend fun buildMessageSegments(
        message: BiliMessage,
        contactStr: String
    ): List<MessageSegment> {
        BiliBiliBot.logger.info("开始构建消息段...")
        val segments = mutableListOf<MessageSegment>()

        try {
            // 获取模板
            BiliBiliBot.logger.info("获取推送模板...")
            val template = when (message) {
                is DynamicMessage -> getTemplate(contactStr, message.mid, "dynamic")
                is LiveMessage -> getTemplate(contactStr, message.mid, "live")
                is LiveCloseMessage -> getTemplate(contactStr, message.mid, "liveClose")
            }
            BiliBiliBot.logger.info("使用模板: $template")

            // 解析模板，分割为多条消息（使用 \r 分隔）
            val messageParts = template.split("\r")
            BiliBiliBot.logger.info("模板分割为 ${messageParts.size} 部分")

            for ((index, part) in messageParts.withIndex()) {
                if (index > 0) {
                    // 在多条消息之间添加分隔符（使用空消息段）
                    segments.add(MessageSegment.text("\n"))
                }

                // 替换模板变量
                BiliBiliBot.logger.info("替换模板变量...")
                val content = replacePlaceholders(part, message)
                BiliBiliBot.logger.info("替换后内容: ${content.take(100)}...")

                // 解析内容，提取图片和文本
                BiliBiliBot.logger.info("解析内容，提取图片和文本...")
                parseContent(content, message, segments)
                BiliBiliBot.logger.info("当前消息段数量: ${segments.size}")
            }

            BiliBiliBot.logger.info("消息段构建完成，共 ${segments.size} 个")
            return segments
        } catch (e: Exception) {
            BiliBiliBot.logger.error("构建消息段失败: ${e.message}", e)
            // 返回简单的文本消息作为降级
            return listOf(MessageSegment.text("${message.name} 发布了新${if (message is DynamicMessage) "动态" else "直播"}"))
        }
    }

    /**
     * 获取推送模板
     */
    private fun getTemplate(contactStr: String, mid: Long, type: String): String {
        val config = BiliConfigManager.config
        val data = BiliData

        // 检查是否有自定义模板
        val templateMap = when (type) {
            "dynamic" -> data.dynamicPushTemplate
            "live" -> data.livePushTemplate
            "liveClose" -> data.liveCloseTemplate
            else -> return ""
        }

        // 查找联系人的自定义模板
        val customTemplate = templateMap.entries.find { (_, contacts) ->
            contactStr in contacts
        }?.key

        // 获取模板内容
        val templateName = customTemplate ?: when (type) {
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

    /**
     * 替换模板占位符
     */
    private fun replacePlaceholders(template: String, message: BiliMessage): String {
        var result = template

        // 公共字段
        result = result.replace("{name}", message.name)
        result = result.replace("{uid}", message.mid.toString())
        result = result.replace("{mid}", message.mid.toString())
        result = result.replace("{time}", message.time)

        // 根据消息类型替换特定字段
        when (message) {
            is DynamicMessage -> {
                result = result.replace("{type}", message.type.text)
                result = result.replace("{did}", message.did)
                result = result.replace("{content}", message.content)
                result = result.replace("{link}", "https://t.bilibili.com/${message.did}")

                // 处理 {links} 占位符
                val linksText = message.links?.joinToString("\n") { "${it.tag}: ${it.value}" } ?: ""
                result = result.replace("{links}", linksText)

                // {images} 占位符在后面处理
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

    /**
     * 解析内容，提取图片和文本
     */
    private suspend fun parseContent(
        content: String,
        message: BiliMessage,
        segments: MutableList<MessageSegment>
    ) {
        var currentText = content

        // 处理 {draw} 占位符（Skiko 生成的图片）
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

        // 处理 {images} 占位符（动态图片）
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

        // 添加剩余的文本内容
        if (currentText.isNotBlank()) {
            segments.add(MessageSegment.text(currentText.trim()))
        }
    }
}
