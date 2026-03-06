package top.bilibili.tasker

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.FilterMode
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.ContactId
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.data.DynamicType
import top.bilibili.napcat.MessageSegment
import top.bilibili.service.AtAllService
import top.bilibili.service.TemplateRenderService
import top.bilibili.utils.parseContactId

/**
 * 娑堟伅鍙戦€佷换鍔?
 * 浠?messageChannel 鎺ユ敹娑堟伅锛岃浆鎹负 OneBot v11 鏍煎紡骞堕€氳繃 NapCat 鍙戦€?
 */
object SendTasker : BiliTasker("SendTasker") {
    override var interval: Int = 1
    override val unitTime: Long = 500
    private const val AT_ALL_WARN_INTERVAL_MS = 60 * 60 * 1000L

    private val messageQueue = Channel<Pair<ContactId, List<MessageSegment>>>(100)
    private val atAllPermissionWarnTs = mutableMapOf<Long, Long>()

    override fun init() {
        BiliBiliBot.logger.info("SendTasker 已启动")

        // 鍚姩娑堟伅闃熷垪澶勭悊鍗忕▼
        launch {
            processMessageQueue()
        }

        // 鍚姩娑堟伅鎺ユ敹澶勭悊鍗忕▼
        launch {
            processMessages()
        }
    }

    override suspend fun main() {
        // 鉁?鏄庣‘琛ㄧず绛夊緟鍙栨秷
        awaitCancellation()
    }

    override fun cancel(cause: CancellationException?) {
        messageQueue.close(cause)
        super.cancel(cause)
    }

    /**
     * 澶勭悊娑堟伅鍙戦€侀槦鍒?
     */
    private suspend fun processMessageQueue() {
        for ((contact, segments) in messageQueue) {
            try {
                val gateway = top.bilibili.service.MessageGatewayProvider.require()
                var success = gateway.sendMessage(contact, segments)

                if (!success && contact.type == "group" && containsAtAllSegment(segments)) {
                    val downgradedSegments = segments.filterNot { it.type == "at" && it.data["qq"] == "all" }
                    if (downgradedSegments.isNotEmpty()) {
                        BiliBiliBot.logger.warn("检测到 @全体 发送失败，尝试降级重发: ${contact.type}:${contact.id}")
                        success = gateway.sendMessage(contact, downgradedSegments)
                        if (success) {
                            notifyAtAllFallback(contact.id)
                        }
                    }
                }

                if (success) {
                    BiliBiliBot.logger.info("消息已发送到 ${contact.type}:${contact.id}")
                } else {
                    BiliBiliBot.logger.warn("消息发送失败: ${contact.type}:${contact.id}")
                }

                // 鍙戦€侀棿闅?
                delay(BiliConfigManager.config.pushConfig.pushInterval)
            } catch (e: Exception) {
                BiliBiliBot.logger.error("发送消息时出错: ${e.message}", e)
            }
        }
    }

    /**
     * 澶勭悊浠?messageChannel 鎺ユ敹鐨勬秷鎭?
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
     * 鍙戦€佹秷鎭埌鎵€鏈夎闃呰€?
     */
    private suspend fun sendToSubscribers(message: BiliMessage) {
        val dynamicMessage = message as? DynamicMessage
        val isPgcMessage = dynamicMessage != null &&
            (dynamicMessage.type == DynamicType.DYNAMIC_TYPE_PGC || dynamicMessage.type == DynamicType.DYNAMIC_TYPE_PGC_UNION)
        val useBangumi = isPgcMessage && dynamicMessage?.pgcSeasonId != null
        val bangumiSub = if (useBangumi) BiliData.bangumi[dynamicMessage?.pgcSeasonId] else null
        val dynamicSub = if (!useBangumi) BiliData.dynamic[message.mid] else null

        if (useBangumi && bangumiSub == null) {
            BiliBiliBot.logger.warn("番剧 ${dynamicMessage?.pgcSeasonId} 没有订阅数据，跳过推送")
            return
        }
        if (!useBangumi && dynamicSub == null) {
            BiliBiliBot.logger.warn("用户 ${message.mid} 没有订阅数据，跳过推送")
            return
        }

        val contacts = if (useBangumi) bangumiSub!!.contacts else dynamicSub!!.contacts
        val banList = if (useBangumi) emptySet() else dynamicSub!!.banList.keys

        BiliBiliBot.logger.info("准备推送到 ${contacts.size} 个联系人")

        // 濡傛灉娑堟伅宸叉寚瀹氳仈绯讳汉锛屽彧鍙戦€佺粰璇ヨ仈绯讳汉
        val specificContact = message.contact
        if (specificContact != null) {
            BiliBiliBot.logger.info("消息指定了联系人: $specificContact")
            val contact = parseContactId(specificContact) ?: return
            if (!shouldSendToContact(message, specificContact)) {
                BiliBiliBot.logger.info("过滤器已拦截发送到 $specificContact")
                return
            }
            val segments = buildMessageSegments(message, specificContact)
            val finalSegments = applyAtAllIfNeeded(contact, specificContact, message, segments)
            messageQueue.send(contact to finalSegments)
            return
        }

        // 鍙戦€佺粰鎵€鏈夎闃呰鐢ㄦ埛鐨勮仈绯讳汉
        for (contactStr in contacts) {
            try {
                BiliBiliBot.logger.info("处理联系人: $contactStr")
                val contact = parseContactId(contactStr) ?: continue

                // 妫€鏌ユ槸鍚﹁绂佺敤
                if (banList.contains(contactStr)) {
                    BiliBiliBot.logger.debug("联系人 $contactStr 已禁用推送")
                    continue
                }

                if (!shouldSendToContact(message, contactStr)) {
                    BiliBiliBot.logger.debug("过滤器已拦截发送到 $contactStr")
                    continue
                }

                // 鏋勫缓娑堟伅娈?
                val segments = buildMessageSegments(message, contactStr)
                BiliBiliBot.logger.info("为联系人 $contactStr 构建了 ${segments.size} 个消息段")

                val finalSegments = applyAtAllIfNeeded(contact, contactStr, message, segments)

                // 鍔犲叆鍙戦€侀槦鍒?
                messageQueue.send(contact to finalSegments)
                BiliBiliBot.logger.info("消息已加入发送队列: ${contact.type}:${contact.id}")

                // 娑堟伅闂撮殧
                delay(BiliConfigManager.config.pushConfig.messageInterval)

            } catch (e: Exception) {
                BiliBiliBot.logger.error("处理联系人 $contactStr 时出错: ${e.message}", e)
            }
        }
    }

    private suspend fun applyAtAllIfNeeded(
        contact: ContactId,
        contactStr: String,
        message: BiliMessage,
        segments: List<MessageSegment>
    ): List<MessageSegment> {
        if (contact.type != "group") return segments
        val alreadyAtAll = segments.any { it.type == "at" && it.data["qq"] == "all" }
        if (alreadyAtAll) return segments
        val atAllEnabled = AtAllService.shouldAtAll(contactStr, message.mid, message)
        if (!atAllEnabled) return segments

        val canAtAll = runCatching {
            BiliBiliBot.isNapCatInitialized() && BiliBiliBot.napCat.canAtAllInGroup(contact.id)
        }.getOrElse {
            BiliBiliBot.logger.warn("检查群 ${contact.id} 的 @全体 权限失败: ${it.message}")
            false
        }

        if (!canAtAll) {
            notifyAtAllPermissionMissing(contact.id, message.mid)
            return segments
        }

        return listOf(MessageSegment.atAll()) + segments
    }

    private suspend fun notifyAtAllPermissionMissing(groupId: Long, uid: Long) {
        val now = System.currentTimeMillis()
        val last = atAllPermissionWarnTs[groupId]
        if (last != null && now - last < AT_ALL_WARN_INTERVAL_MS) return
        atAllPermissionWarnTs[groupId] = now

        val notice = "群 $groupId 已配置 At全体(UID: $uid)，但 Bot 无 @全体 权限，已自动降级为普通推送。请将 Bot 设为管理员。"
        runCatching { top.bilibili.service.MessageGatewayProvider.require().sendAdminMessage(notice) }
            .onFailure { BiliBiliBot.logger.warn("发送 @全体 降级提醒失败: ${it.message}") }
    }

    private suspend fun notifyAtAllFallback(groupId: Long) {
        val now = System.currentTimeMillis()
        val last = atAllPermissionWarnTs[groupId]
        if (last != null && now - last < AT_ALL_WARN_INTERVAL_MS) return
        atAllPermissionWarnTs[groupId] = now

        val notice = "群 $groupId 的 @全体 推送发送失败，已自动降级为普通推送。请检查 Bot 是否具备 @全体 权限或当日次数是否耗尽。"
        runCatching { top.bilibili.service.MessageGatewayProvider.require().sendAdminMessage(notice) }
            .onFailure { BiliBiliBot.logger.warn("发送 @全体 重试降级提醒失败: ${it.message}") }
    }

    private fun containsAtAllSegment(segments: List<MessageSegment>): Boolean {
        return segments.any { it.type == "at" && it.data["qq"] == "all" }
    }

    private fun shouldSendToContact(message: BiliMessage, contactStr: String): Boolean {
        return when (message) {
            is DynamicMessage -> shouldSendDynamicToContact(message, contactStr)
            else -> true
        }
    }

    private fun shouldSendDynamicToContact(message: DynamicMessage, contactStr: String): Boolean {
        val filterKey = if (
            (message.type == DynamicType.DYNAMIC_TYPE_PGC || message.type == DynamicType.DYNAMIC_TYPE_PGC_UNION) &&
            message.pgcSeasonId != null
        ) {
            message.pgcSeasonId
        } else {
            message.mid
        }
        val dynamicFilter = getDynamicFilter(contactStr, filterKey) ?: return true

        if (!passesTypeFilter(message.type, dynamicFilter)) return false
        if (!passesRegularFilter(message.content, dynamicFilter)) return false

        return true
    }

    private fun getDynamicFilter(contactStr: String, mid: Long): DynamicFilter? {
        val byContact = BiliData.filter[contactStr] ?: return null
        return byContact[mid] ?: byContact[0L]
    }

    private fun passesTypeFilter(type: DynamicType, filter: DynamicFilter): Boolean {
        val typeSelect = filter.typeSelect
        if (typeSelect.list.isEmpty()) return true

        val mappedType = mapDynamicType(type)
        return when (typeSelect.mode) {
            FilterMode.WHITE_LIST -> mappedType in typeSelect.list
            FilterMode.BLACK_LIST -> mappedType !in typeSelect.list
        }
    }

    private fun passesRegularFilter(content: String, filter: DynamicFilter): Boolean {
        val regularSelect = filter.regularSelect
        if (regularSelect.list.isEmpty()) return true

        val text = content.ifEmpty { "" }
        return when (regularSelect.mode) {
            FilterMode.WHITE_LIST -> {
                regularSelect.list.all { pattern ->
                    val regex = runCatching { Regex(pattern) }.getOrNull()
                    if (regex == null) {
                        BiliBiliBot.logger.warn("忽略非法正则: $pattern")
                        true
                    } else {
                        regex.containsMatchIn(text)
                    }
                }
            }
            FilterMode.BLACK_LIST -> {
                regularSelect.list.none { pattern ->
                    val regex = runCatching { Regex(pattern) }.getOrNull()
                    if (regex == null) {
                        BiliBiliBot.logger.warn("忽略非法正则: $pattern")
                        false
                    } else {
                        regex.containsMatchIn(text)
                    }
                }
            }
        }
    }

    private fun mapDynamicType(type: DynamicType): DynamicFilterType {
        return when (type) {
            DynamicType.DYNAMIC_TYPE_FORWARD -> DynamicFilterType.FORWARD
            DynamicType.DYNAMIC_TYPE_ARTICLE -> DynamicFilterType.ARTICLE
            DynamicType.DYNAMIC_TYPE_AV -> DynamicFilterType.VIDEO
            DynamicType.DYNAMIC_TYPE_MUSIC -> DynamicFilterType.MUSIC
            DynamicType.DYNAMIC_TYPE_LIVE,
            DynamicType.DYNAMIC_TYPE_LIVE_RCMD -> DynamicFilterType.LIVE
            else -> DynamicFilterType.DYNAMIC
        }
    }

    /**
     * 鏋勫缓 OneBot v11 娑堟伅娈?
     */
    private suspend fun buildMessageSegments(
        message: BiliMessage,
        contactStr: String
    ): List<MessageSegment> {
        return TemplateRenderService.buildSegments(message, contactStr)
    }
}

