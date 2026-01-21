package top.bilibili.tasker

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.napcat.MessageEvent
import top.bilibili.napcat.MessageSegment
import top.bilibili.service.TriggerMode
import top.bilibili.service.matchingRegular
import top.bilibili.utils.ImageCache
import top.bilibili.utils.logger

/**
 * 链接监听和解析任务
 * 监听群消息中的 B站链接，自动解析并发送解析结果
 */
object ListenerTasker : BiliTasker("ListenerTasker") {
    override var interval: Int = -1

    private val triggerMode get() = BiliConfigManager.config.linkResolveConfig.triggerMode
    private val returnLink get() = BiliConfigManager.config.linkResolveConfig.returnLink

    // 缓存最近解析的链接，避免无限循环（记录 群ID+链接 -> 时间戳）
    private val recentlyParsedLinks = mutableMapOf<String, Long>()
    private val cacheDuration = 60_000L // 60 秒缓存时间
    private val cacheMutex = Mutex() // 缓存锁

    override fun init() {
        BiliBiliBot.logger.info("ListenerTasker 已启动")

        // 启动消息监听协程
        BiliBiliBot.launch {
            listenMessages()
        }

        // 启动缓存清理协程
        BiliBiliBot.launch {
            while (true) {
                delay(5000) // 每 5 秒清理一次
                cleanExpiredCache()
            }
        }
    }

    /**
     * 清理过期的缓存
     */
    private suspend fun cleanExpiredCache() {
        val now = System.currentTimeMillis()
        cacheMutex.withLock {
            val expired = recentlyParsedLinks.filter { now - it.value > cacheDuration }
            expired.keys.forEach { recentlyParsedLinks.remove(it) }
            if (expired.isNotEmpty()) {
                logger.debug("清理了 ${expired.size} 条过期的链接缓存")
            }
        }
    }

    override suspend fun main() {
        // 主循环在 init() 中启动的协程中运行
        delay(Long.MAX_VALUE)
    }

    /**
     * 监听 NapCat 消息事件
     */
    private suspend fun listenMessages() {
        BiliBiliBot.napCat.eventFlow.collect { event ->
            try {
                // 只处理群消息
                if (event.messageType == "group") {
                    handleGroupMessage(event)
                }
            } catch (e: Exception) {
                logger.error("处理链接解析事件失败: ${e.message}", e)
            }
        }
    }

    /**
     * 处理群消息
     */
    private suspend fun handleGroupMessage(event: MessageEvent) {
        val groupId = event.groupId ?: return

        // 过滤掉 Bot 自己发送的消息，避免无限循环
        // event.selfId 是 Bot 的 QQ 号，event.userId 是消息发送者的 QQ 号
        if (event.selfId != 0L && event.userId == event.selfId) {
            logger.debug("忽略 Bot 自己发送的消息 (selfId=${event.selfId}, userId=${event.userId})")
            return
        }

        // 检查触发模式
        val shouldProcess = when (triggerMode) {
            TriggerMode.At -> {
                // 检查是否包含 @ 消息段
                event.message.any { it.type == "at" }
            }
            TriggerMode.Always -> true
            TriggerMode.Never -> false
        }

        if (!shouldProcess) return

        // 提取所有可能的链接
        val allLinks = mutableListOf<String>()

        // 提取文本内容中的链接
        val textContent = event.message
            .filter { it.type == "text" }
            .joinToString("") { it.data["text"] ?: "" }
            .trim()

        if (textContent.isNotEmpty()) {
            allLinks.add(textContent)
        }

        // 尝试从 QQ 小程序中提取链接
        val miniAppUrl = extractMiniAppUrl(event.message)
        if (miniAppUrl != null) {
            logger.info("检测到 QQ 小程序分享，提取链接: $miniAppUrl")
            allLinks.add(miniAppUrl)
        }

        if (allLinks.isEmpty()) return

        // 匹配所有有效的 B站链接
        val matchedLinks = mutableListOf<Pair<String, top.bilibili.service.ResolvedLinkInfo>>()

        for (link in allLinks) {
            val infos = top.bilibili.service.matchingAllRegular(link)
            for (info in infos) {
                matchedLinks.add(link to info)
            }
        }

        if (matchedLinks.isEmpty()) return

        // 逐个解析并发送每个链接
        for ((matchedLink, linkInfo) in matchedLinks) {
            // 使用解析后的 ID 作为缓存 key（群ID + 类型 + ID）
            val resolvedId = "${linkInfo.type}:${linkInfo.id}"
            val cacheKey = "$groupId:$resolvedId"
            val now = System.currentTimeMillis()

            // 检查是否在缓存中（60 秒内解析过）
            val shouldSkip = cacheMutex.withLock {
                val lastParsedTime = recentlyParsedLinks[cacheKey]
                if (lastParsedTime != null && now - lastParsedTime < cacheDuration) {
                    true // 需要跳过
                } else {
                    // 记录本次解析
                    recentlyParsedLinks[cacheKey] = now
                    false // 不跳过
                }
            }

            if (shouldSkip) {
                logger.debug("忽略重复链接: $resolvedId (60秒内已解析)")
                continue
            }

            logger.info("匹配到链接: $matchedLink -> ${linkInfo.type} (ID: ${linkInfo.id})")

            try {
                // 解析链接并生成图片
                logger.info("开始解析链接 -> $matchedLink")
                
                var imagePath: String? = null
                var retryCount = 0
                val maxRetries = 1

                while (true) {
                    try {
                        imagePath = linkInfo.drawGeneral()
                        break
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        val isTargetError = msg.contains("CODE: 500") || msg.contains("MISS_OPUS_DETAIL_DATA")
                        
                        if (isTargetError && retryCount < maxRetries) {
                            retryCount++
                            logger.warn("解析链接失败，3秒后重试 ($retryCount/$maxRetries): $msg")
                            delay(3000)
                        } else {
                            throw e
                        }
                    }
                }

                if (imagePath == null) {
                    logger.warn("链接解析失败，返回 null")
                    BiliBiliBot.sendGroupMessage(groupId, listOf(
                        MessageSegment.text("解析失败")
                    ))
                    continue
                }

                logger.info("链接解析成功，图片路径: $imagePath")

                // 将生成的图片路径转换为 file:// 协议
                val imageUrl = ImageCache.toFileUrl(imagePath)
                logger.info("转换后的图片 URL: $imageUrl")

                // 构建回复消息
                val replySegments = mutableListOf<MessageSegment>()
                replySegments.add(MessageSegment.image(imageUrl))

                // 如果配置了 returnLink，在回复中包含链接
                if (returnLink) {
                    val link = linkInfo.getLink()
                    // 将链接转换为标准格式（非短链接）
                    val standardLink = convertToStandardLink(link, linkInfo)
                    replySegments.add(MessageSegment.text("\n$standardLink"))
                }

                logger.info("准备发送链接解析结果到群 $groupId")

                // 发送解析结果
                val success = BiliBiliBot.sendGroupMessage(groupId, replySegments)

                if (success) {
                    logger.info("链接解析结果已发送到群 $groupId")
                } else {
                    logger.warn("链接解析结果发送失败")
                    BiliBiliBot.sendGroupMessage(groupId, listOf(
                        MessageSegment.text("图片上传失败")
                    ))
                }

                // 如果还有更多链接要处理，添加短暂延迟避免发送过快
                if (matchedLinks.indexOf(matchedLink to linkInfo) < matchedLinks.size - 1) {
                    delay(500)
                }

            } catch (e: Exception) {
                logger.error("解析链接时出错: ${e.message}", e)
                BiliBiliBot.sendGroupMessage(groupId, listOf(
                    MessageSegment.text("解析失败: ${e.message}")
                ))
            }
        }
    }

    /**
     * 从 QQ 小程序消息中提取 B站链接
     * 支持从小程序的 JSON 数据中提取 qqdocurl 字段
     */
    private fun extractMiniAppUrl(messageSegments: List<MessageSegment>): String? {
        try {
            // 查找 json 类型的消息段
            val jsonSegment = messageSegments.find { it.type == "json" } ?: return null
            val jsonData = jsonSegment.data["data"] ?: return null

            logger.debug("检测到 JSON 消息，尝试提取链接...")

            // 使用简单的正则表达式提取 qqdocurl 字段
            // JSON 格式: "qqdocurl" : "https://b23.tv/xxxxx"
            val qqdocurlRegex = """"qqdocurl"\s*:\s*"([^"]+)"""".toRegex()
            val match = qqdocurlRegex.find(jsonData)

            if (match != null) {
                val url = match.groupValues[1]
                    .replace("\\/", "/") // 反转义斜杠
                    .replace("&#44;", ",") // 反转义逗号
                logger.info("从小程序中提取到 qqdocurl 链接: $url")
                return url
            }

            // 尝试提取 jumpUrl 字段（HD版可能使用这个字段）
            val jumpUrlRegex = """"jumpUrl"\s*:\s*"([^"]+)"""".toRegex()
            val jumpMatch = jumpUrlRegex.find(jsonData)
            if (jumpMatch != null) {
                val url = jumpMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("&#44;", ",")
                logger.info("从小程序中提取到 jumpUrl 链接: $url")
                return url
            }

            // 尝试提取 url 字段
            val urlFieldRegex = """"url"\s*:\s*"([^"]+bilibili[^"]+)"""".toRegex()
            val urlFieldMatch = urlFieldRegex.find(jsonData)
            if (urlFieldMatch != null) {
                val url = urlFieldMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("&#44;", ",")
                logger.info("从小程序中提取到 url 字段链接: $url")
                return url
            }

            // 如果没有找到特定字段，尝试查找任何 B站链接
            val bilibiliUrlRegex = """(https?://[^\s"]+bilibili[^\s"]+)""".toRegex()
            val urlMatch = bilibiliUrlRegex.find(jsonData)
            if (urlMatch != null) {
                val url = urlMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("&#44;", ",")
                logger.info("从小程序中提取到 B站链接: $url")
                return url
            }

            logger.warn("JSON 消息中未找到 B站链接，JSON 数据前 200 字符: ${jsonData.take(200)}")
            return null
        } catch (e: Exception) {
            logger.warn("提取小程序链接失败: ${e.message}", e)
            return null
        }
    }

    /**
     * 将链接转换为标准格式（非短链接）
     */
    private fun convertToStandardLink(link: String, linkInfo: top.bilibili.service.ResolvedLinkInfo): String {
        return when (linkInfo.type) {
            top.bilibili.service.LinkType.VideoLink -> {
                // 视频链接：确保使用标准格式
                val id = linkInfo.id
                val videoId = if (id.contains("BV") || id.contains("av")) id else "av$id"
                "https://www.bilibili.com/video/$videoId"
            }
            top.bilibili.service.LinkType.Article -> {
                // 专栏链接
                "https://www.bilibili.com/read/cv${linkInfo.id}"
            }
            top.bilibili.service.LinkType.Dynamic -> {
                // 动态链接
                "https://t.bilibili.com/${linkInfo.id}"
            }
            top.bilibili.service.LinkType.Live -> {
                // 直播链接
                "https://live.bilibili.com/${linkInfo.id}"
            }
            top.bilibili.service.LinkType.User -> {
                // 用户空间链接
                "https://space.bilibili.com/${linkInfo.id}"
            }
            top.bilibili.service.LinkType.Pgc -> {
                // 番剧链接
                val id = linkInfo.id
                if (id.startsWith("md")) {
                    "https://www.bilibili.com/bangumi/media/$id"
                } else {
                    "https://www.bilibili.com/bangumi/play/$id"
                }
            }
            top.bilibili.service.LinkType.ShortLink -> {
                // 短链接（理论上不应该到这里，因为已经在匹配阶段解析了）
                link
            }
        }
    }
}
