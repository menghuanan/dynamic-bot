package top.bilibili.tasker

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import top.bilibili.BiliConfigManager
import top.bilibili.connector.CapabilityGuard
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.core.BiliBiliBot
import top.bilibili.service.FeatureSwitchService
import top.bilibili.service.LinkResolvePolicyService
import top.bilibili.service.MessageGatewayProvider
import top.bilibili.service.ResolvedLinkInfo
import top.bilibili.service.TriggerMode
import top.bilibili.service.sendPartsWithCapabilityFallback
import top.bilibili.service.matchingAllRegular
import top.bilibili.utils.toSubject
import top.bilibili.utils.logger

object ListenerTasker : BiliTasker("ListenerTasker") {
    override var interval: Int = -1
    override val wrapMainInBusinessLifecycle = false

    private val linkResolvePolicyService = LinkResolvePolicyService()
    private val triggerMode get() = BiliConfigManager.config.linkResolveConfig.triggerMode
    private val returnLink get() = BiliConfigManager.config.linkResolveConfig.returnLink
    private const val TOO_MANY_REQUESTS_NOTICE = "请求太多次啦，冷静一下吧~"

    override fun init() {
        BiliBiliBot.logger.info("ListenerTasker 已启动")

        launchManagedWorker("listener-loop") {
            listenMessages()
        }
    }

    override suspend fun main() {
        awaitCancellation()
    }

    private suspend fun listenMessages() {
        BiliBiliBot.requireConnectorManager().eventFlow.collect { event ->
            try {
                if (event.chatType == PlatformChatType.GROUP) {
                    handleGroupMessage(event)
                }
            } catch (e: Exception) {
                logger.error("处理链接解析事件失败: ${e.message}", e)
            }
        }
    }

    private suspend fun handleGroupMessage(event: PlatformInboundMessage) {
        if (event.fromSelf) {
            logger.debug("忽略 Bot 自己发送的消息 (selfId=${event.selfId}, userId=${event.senderId})")
            return
        }
        val groupContact = event.chatContact
        val senderContact = event.senderContact
        val senderSubject = senderContact.toSubject()

        if (top.bilibili.BiliData.linkParseBlacklistContacts.contains(senderSubject)) {
            logger.debug("忽略黑名单用户 {} 的链接解析请求", senderContact.id)
            return
        }

        val shouldProcess = when (triggerMode) {
            TriggerMode.At -> event.hasMention
            TriggerMode.Always -> true
            TriggerMode.Never -> false
        }

        if (!shouldProcess) return
        if (event.searchTexts.isEmpty()) return

        val matchedLinks = mutableListOf<Pair<String, ResolvedLinkInfo>>()
        for (link in event.searchTexts) {
            val infos = matchingAllRegular(link, groupContact.toSubject())
            for (info in infos) {
                matchedLinks.add(link to info)
            }
        }

        if (matchedLinks.isEmpty()) return

        // 只在确认本次事件确实要进入链接解析回复分支时再判断能力，避免为无关消息触发额外探测。
        val linkResolveGuard = PlatformCapabilityService.guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.LINK_RESOLVE,
                contact = groupContact,
            ),
        )
        if (linkResolveGuard.stopCurrentOperation) {
            logger.warn(
                "{}: 停止当前群 {} 的链接解析能力分支",
                linkResolveGuard.marker ?: CapabilityGuard.UNSUPPORTED_MESSAGE,
                groupContact.toSubject(),
            )
            return
        }

        val policyDecision = linkResolvePolicyService.applyPolicy(
            groupKey = groupContact.toSubject(),
            userKey = senderSubject,
            candidates = matchedLinks.map { it.second },
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
            processApprovedMatchedLinks(groupContact, approvedMatchedLinks)
        }

        if (policyDecision.shouldWarnTooManyRequests) {
            MessageGatewayProvider.require().sendMessageGuarded(
                groupContact,
                listOf(OutgoingPart.text(TOO_MANY_REQUESTS_NOTICE)),
            )
        }
    }

    private suspend fun processApprovedMatchedLinks(
        groupContact: top.bilibili.connector.PlatformContact,
        matchedLinks: List<Pair<String, ResolvedLinkInfo>>,
    ) {
        for ((index, entry) in matchedLinks.withIndex()) {
            val (matchedLink, linkInfo) = entry
            logger.info("匹配到链接 $matchedLink -> ${linkInfo.type} (ID: ${linkInfo.id})")

            try {
                val link = linkInfo.getLink()
                val standardLink = convertToStandardLink(link, linkInfo)
                val canDraw = FeatureSwitchService.canRenderLinkResolveDraw()

                if (!canDraw) {
                    val success = MessageGatewayProvider.require().sendMessage(
                        groupContact,
                        listOf(OutgoingPart.text(standardLink)),
                    )

                    if (!success) {
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
                        val isTargetError = msg.contains("CODE: 500") || msg.contains("MISS_OPUS_DETAIL_DATA")

                        if (isTargetError && retryCount < maxRetries) {
                            retryCount++
                            logger.warn("解析链接失败，3 秒后重试 ($retryCount/$maxRetries): $msg")
                            delay(3000)
                        } else {
                            throw e
                        }
                    }
                }

                if (imagePath == null) {
                    MessageGatewayProvider.require().sendMessageGuarded(
                        groupContact,
                        listOf(OutgoingPart.text("解析失败")),
                    )
                    continue
                }

                val replySegments = mutableListOf<OutgoingPart>()
                replySegments.add(OutgoingPart.image(imagePath))

                if (returnLink) {
                    replySegments.add(OutgoingPart.text("\n$standardLink"))
                }

                val success = sendPartsWithCapabilityFallback(
                    groupContact,
                    replySegments,
                    fallbackText = standardLink,
                )

                if (!success) {
                    logger.warn("链接解析结果发送失败")
                    MessageGatewayProvider.require().sendMessageGuarded(
                        groupContact,
                        listOf(OutgoingPart.text("图片上传失败")),
                    )
                }

                if (index < matchedLinks.lastIndex) {
                    delay(500)
                }
            } catch (e: Exception) {
                logger.error("解析链接时出错: ${e.message}", e)
                MessageGatewayProvider.require().sendMessageGuarded(
                    groupContact,
                    listOf(OutgoingPart.text("解析失败: ${e.message}")),
                )
            }
        }
    }

    private fun convertToStandardLink(link: String, linkInfo: ResolvedLinkInfo): String {
        return when (val type = linkInfo.type) {
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
            is top.bilibili.service.LinkType.OpusWithCv -> {
                type.cvId?.let { "https://www.bilibili.com/read/cv$it" }
                    ?: "https://www.bilibili.com/opus/${type.opusId}"
            }
        }
    }
}
