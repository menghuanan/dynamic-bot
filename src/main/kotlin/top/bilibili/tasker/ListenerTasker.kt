package top.bilibili.tasker

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.napcat.MessageEvent
import top.bilibili.napcat.MessageSegment
import top.bilibili.service.FeatureSwitchService
import top.bilibili.service.LinkResolvePolicyService
import top.bilibili.service.ResolvedLinkInfo
import top.bilibili.service.TriggerMode
import top.bilibili.service.matchingAllRegular
import top.bilibili.utils.ImageCache
import top.bilibili.utils.logger

/**
 * 链接监听和解析任务
 * 监听群消息中的 B站链接，按策略解析并发送结果
 */
object ListenerTasker : BiliTasker("ListenerTasker") {
    override var interval: Int = -1
    override val wrapMainInBusinessLifecycle = false

    private val linkResolvePolicyService = LinkResolvePolicyService()
    private val triggerMode get() = BiliConfigManager.config.linkResolveConfig.triggerMode
    private val returnLink get() = BiliConfigManager.config.linkResolveConfig.returnLink
    private const val TOO_MANY_REQUESTS_NOTICE = "请求太多次啦，冷静一下吧~"

    override fun init() {
        BiliBiliBot.logger.info("ListenerTasker 已启动")

        launch {
            listenMessages()
        }
    }

    override suspend fun main() {
        awaitCancellation()
    }

    private suspend fun listenMessages() {
        BiliBiliBot.napCat.eventFlow.collect { event ->
            try {
                if (event.messageType == "group") {
                    handleGroupMessage(event)
                }
            } catch (e: Exception) {
                logger.error("处理链接解析事件失败: ${e.message}", e)
            }
        }
    }

    private suspend fun handleGroupMessage(event: MessageEvent) {
        val groupId = event.groupId ?: return
        val userId = event.userId

        if (top.bilibili.BiliData.linkParseBlacklist.contains(userId)) {
            logger.debug("忽略黑名单用户 $userId 的链接解析请求")
            return
        }

        if (event.selfId != 0L && event.userId == event.selfId) {
            logger.debug("忽略 Bot 自己发送的消息 (selfId=${event.selfId}, userId=${event.userId})")
            return
        }

        val shouldProcess = when (triggerMode) {
            TriggerMode.At -> event.message.any { it.type == "at" }
            TriggerMode.Always -> true
            TriggerMode.Never -> false
        }

        if (!shouldProcess) return

        val allLinks = mutableListOf<String>()
        val textContent = event.message
            .filter { it.type == "text" }
            .joinToString("") { it.data["text"] ?: "" }
            .trim()

        if (textContent.isNotEmpty()) {
            allLinks.add(textContent)
        }

        val miniAppUrl = extractMiniAppUrl(event.message)
        if (miniAppUrl != null) {
            logger.info("检测到 QQ 小程序分享，提取链接: $miniAppUrl")
            allLinks.add(miniAppUrl)
        }

        if (allLinks.isEmpty()) return

        val matchedLinks = mutableListOf<Pair<String, ResolvedLinkInfo>>()
        for (link in allLinks) {
            val infos = matchingAllRegular(link, "group:$groupId")
            for (info in infos) {
                matchedLinks.add(link to info)
            }
        }

        if (matchedLinks.isEmpty()) return

        val policyDecision = linkResolvePolicyService.applyPolicy(
            groupId = groupId,
            userId = userId,
            candidates = matchedLinks.map { it.second }
        )

        val approvedQueue = ArrayDeque(policyDecision.approvedLinks)
        val approvedMatchedLinks = mutableListOf<Pair<String, ResolvedLinkInfo>>()
        for (matchedLink in matchedLinks) {
            if (approvedQueue.isEmpty()) break
            if (matchedLink.second == approvedQueue.first()) {
                approvedMatchedLinks += matchedLink
                approvedQueue.removeFirst()
            }
        }

        if (approvedMatchedLinks.isNotEmpty()) {
            processApprovedMatchedLinks(groupId, approvedMatchedLinks)
        }

        if (policyDecision.shouldWarnTooManyRequests) {
            top.bilibili.service.MessageGatewayProvider.require().sendGroupMessage(
                groupId,
                listOf(MessageSegment.text(TOO_MANY_REQUESTS_NOTICE))
            )
        }
    }

    private suspend fun processApprovedMatchedLinks(
        groupId: Long,
        matchedLinks: List<Pair<String, ResolvedLinkInfo>>
    ) {
        for ((index, entry) in matchedLinks.withIndex()) {
            val (matchedLink, linkInfo) = entry
            logger.info("匹配到链接: $matchedLink -> ${linkInfo.type} (ID: ${linkInfo.id})")

            try {
                logger.info("开始解析链接 -> $matchedLink")

                val link = linkInfo.getLink()
                val standardLink = convertToStandardLink(link, linkInfo)
                val canDraw = FeatureSwitchService.canRenderLinkResolveDraw()

                if (!canDraw) {
                    val success = top.bilibili.service.MessageGatewayProvider.require().sendGroupMessage(
                        groupId,
                        listOf(MessageSegment.text(standardLink))
                    )

                    if (success) {
                        logger.info("链接解析结果已发送到群 $groupId")
                    } else {
                        logger.warn("链接解析结果发送失败")
                    }

                    if (index < matchedLinks.lastIndex) {
                        delay(500)
                    }
                    continue
                }

                var imagePath: String? = null
                var retryCount = 0
                val maxRetries = 1

                while (true) {
                    try {
                        imagePath = linkInfo.drawGeneral()
                        break
                    } catch (e: Exception) {
                        val msg = e.message ?: ""
                        val isTargetError =
                            msg.contains("CODE: 500") || msg.contains("MISS_OPUS_DETAIL_DATA")

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
                    top.bilibili.service.MessageGatewayProvider.require().sendGroupMessage(
                        groupId,
                        listOf(MessageSegment.text("解析失败"))
                    )
                    continue
                }

                logger.info("链接解析成功，图片路径: $imagePath")

                val imageUrl = ImageCache.toFileUrl(imagePath)
                logger.info("转换后的图片 URL: $imageUrl")

                val replySegments = mutableListOf<MessageSegment>()
                replySegments.add(MessageSegment.image(imageUrl))

                if (returnLink) {
                    replySegments.add(MessageSegment.text("\n$standardLink"))
                }

                logger.info("准备发送链接解析结果到群 $groupId")

                val success = top.bilibili.service.MessageGatewayProvider.require()
                    .sendGroupMessage(groupId, replySegments)

                if (success) {
                    logger.info("链接解析结果已发送到群 $groupId")
                } else {
                    logger.warn("链接解析结果发送失败")
                    top.bilibili.service.MessageGatewayProvider.require().sendGroupMessage(
                        groupId,
                        listOf(MessageSegment.text("图片上传失败"))
                    )
                }

                if (index < matchedLinks.lastIndex) {
                    delay(500)
                }
            } catch (e: Exception) {
                logger.error("解析链接时出错: ${e.message}", e)
                top.bilibili.service.MessageGatewayProvider.require().sendGroupMessage(
                    groupId,
                    listOf(MessageSegment.text("解析失败: ${e.message}"))
                )
            }
        }
    }

    /**
     * 从 QQ 小程序消息中提取 B站链接
     */
    private fun extractMiniAppUrl(messageSegments: List<MessageSegment>): String? {
        try {
            val jsonSegment = messageSegments.find { it.type == "json" } ?: return null
            val jsonData = jsonSegment.data["data"] ?: return null

            logger.debug("检测到 JSON 消息，尝试提取链接...")

            val qqdocurlRegex = """"qqdocurl"\s*:\s*"([^"]+)"""".toRegex()
            val match = qqdocurlRegex.find(jsonData)
            if (match != null) {
                val url = match.groupValues[1]
                    .replace("\\/", "/")
                    .replace("&#44;", ",")
                logger.info("从小程序中提取到 qqdocurl 链接: $url")
                return url
            }

            val jumpUrlRegex = """"jumpUrl"\s*:\s*"([^"]+)"""".toRegex()
            val jumpMatch = jumpUrlRegex.find(jsonData)
            if (jumpMatch != null) {
                val url = jumpMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("&#44;", ",")
                logger.info("从小程序中提取到 jumpUrl 链接: $url")
                return url
            }

            val urlFieldRegex = """"url"\s*:\s*"([^"]+bilibili[^"]+)"""".toRegex()
            val urlFieldMatch = urlFieldRegex.find(jsonData)
            if (urlFieldMatch != null) {
                val url = urlFieldMatch.groupValues[1]
                    .replace("\\/", "/")
                    .replace("&#44;", ",")
                logger.info("从小程序中提取到 url 字段链接: $url")
                return url
            }

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
    private fun convertToStandardLink(link: String, linkInfo: ResolvedLinkInfo): String {
        return when (linkInfo.type) {
            top.bilibili.service.LinkType.VideoLink -> {
                val id = linkInfo.id
                val videoId = if (id.contains("BV") || id.contains("av")) id else "av$id"
                "https://www.bilibili.com/video/$videoId"
            }
            top.bilibili.service.LinkType.Article -> "https://www.bilibili.com/read/cv${linkInfo.id}"
            top.bilibili.service.LinkType.Dynamic -> "https://t.bilibili.com/${linkInfo.id}"
            top.bilibili.service.LinkType.Live -> "https://live.bilibili.com/${linkInfo.id}"
            top.bilibili.service.LinkType.User -> "https://space.bilibili.com/${linkInfo.id}"
            top.bilibili.service.LinkType.Pgc -> {
                val id = linkInfo.id
                if (id.startsWith("md")) {
                    "https://www.bilibili.com/bangumi/media/$id"
                } else {
                    "https://www.bilibili.com/bangumi/play/$id"
                }
            }
            top.bilibili.service.LinkType.ShortLink -> link
        }
    }
}
