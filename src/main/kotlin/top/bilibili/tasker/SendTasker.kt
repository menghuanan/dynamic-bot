package top.bilibili.tasker

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.FilterMode
import top.bilibili.SubData
import top.bilibili.connector.CapabilityGuard
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.connector.PlatformContact
import top.bilibili.core.BiliBiliBot
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.data.DynamicType
import top.bilibili.service.AtAllService
import top.bilibili.service.SelectedTemplate
import top.bilibili.service.TemplateSelectionService
import top.bilibili.service.TemplateRenderService
import top.bilibili.utils.actionNotify
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parsePlatformContact
import top.bilibili.utils.subjectsEquivalent
import top.bilibili.utils.toSubject

/**
 * 消息发送任务
 * 从 messageChannel 接收消息，转换为 OneBot v11 格式并通过 NapCat 发送
 */
object SendTasker : BiliTasker("SendTasker") {
    override var interval: Int = 1
    override val unitTime: Long = 500
    override val wrapMainInBusinessLifecycle = false
    private const val AT_ALL_WARN_INTERVAL_MS = 60 * 60 * 1000L
    private const val AT_ALL_WARN_RETAIN_MS = 24 * 60 * 60 * 1000L
    private const val AT_ALL_WARN_SWEEP_INTERVAL_MS = 10 * 60 * 1000L
    private const val AT_ALL_WARN_CACHE_MAX_SIZE = 1024

    private val messageQueue = Channel<Pair<PlatformContact, List<OutgoingPart>>>(100)
    private val atAllPermissionWarnTs = mutableMapOf<String, Long>()
    private var lastAtAllWarnSweepTs = 0L

    override fun init() {
        BiliBiliBot.logger.info("SendTasker 已启动")

        // 启动消息队列处理协程
        launchManagedWorker("send-queue-loop") {
            processMessageQueue()
        }

        // 启动消息接收处理协程
        launchManagedWorker("message-dispatch-loop") {
            processMessages()
        }
    }

    override suspend fun main() {
        // 明确表示等待取消
        awaitCancellation()
    }

    override fun cancel(cause: CancellationException?) {
        messageQueue.close(cause)
        synchronized(atAllPermissionWarnTs) {
            // 任务关闭时立即释放节流表，避免对象在下一轮生命周期前持续占用内存。
            atAllPermissionWarnTs.clear()
            lastAtAllWarnSweepTs = 0L
        }
        super.cancel(cause)
    }

    /**
     * 处理消息发送队列
     */
    private suspend fun processMessageQueue() {
        for ((contact, segments) in messageQueue) {
            if (BiliBiliBot.isStopping()) {
                BiliBiliBot.logger.info("停机期间停止 SendTasker 队列处理协程")
                return
            }
            try {
                val gateway = top.bilibili.service.MessageGatewayProvider.require()
                var success = gateway.sendMessageGuarded(contact, segments)

                if (!success && contact.type == PlatformChatType.GROUP && containsAtAllSegment(segments)) {
                    // @全体 失败时优先降级普通消息，避免整条推送因权限或次数问题完全丢失。
                    val downgradedSegments = segments.filterNot { it is OutgoingPart.MentionAll }
                    if (downgradedSegments.isNotEmpty()) {
                        BiliBiliBot.logger.warn("检测到 @全体 发送失败，尝试降级重发: ${contact.toSubject()}")
                        success = gateway.sendMessageGuarded(contact, downgradedSegments)
                        if (success) {
                            notifyAtAllFallback(contact.id)
                        }
                    }
                }

                if (success) {
                    BiliBiliBot.logger.info("消息已发送到 {}", contact.toSubject())
                } else {
                    BiliBiliBot.logger.warn("消息发送失败: {}", contact.toSubject())
                }

                // 鍙戦€侀棿闅?
                delay(BiliConfigManager.config.pushConfig.pushInterval)
            } catch (e: CancellationException) {
                if (BiliBiliBot.isStopping()) {
                    BiliBiliBot.logger.info("停机期间取消 SendTasker 队列处理协程")
                    return
                }
                throw e
            } catch (e: Exception) {
                if (BiliBiliBot.isStopping()) {
                    BiliBiliBot.logger.info("停机期间丢弃队列消息: ${e.message}")
                    return
                }
                BiliBiliBot.logger.error("发送消息时出错: ${e.message}", e)
            }
        }
    }

    /**
     * 处理从 messageChannel 接收的消息
     */
    private suspend fun processMessages() {
        for (message in BiliBiliBot.messageChannel) {
            if (BiliBiliBot.isStopping()) {
                BiliBiliBot.logger.info("停机期间停止 SendTasker 消息分发协程")
                return
            }
            try {
                BiliBiliBot.logger.info("从 messageChannel 接收到消息: ${message.name} (${message.mid})")
                sendToSubscribers(message)
            } catch (e: CancellationException) {
                if (BiliBiliBot.isStopping()) {
                    BiliBiliBot.logger.info("停机期间取消 SendTasker 消息分发协程")
                    return
                }
                throw e
            } catch (e: Exception) {
                if (BiliBiliBot.isStopping()) {
                    BiliBiliBot.logger.info("停机期间丢弃待处理消息: ${e.message}")
                    return
                }
                BiliBiliBot.logger.error("处理消息失败: ${e.message}", e)
            }
        }
    }

    /**
     * 发送消息到所有订阅者
     */
    private suspend fun sendToSubscribers(message: BiliMessage) {
        val messageIdentity = buildMessageIdentity(message)
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

        // 如果消息已指定联系人，只发送给该联系人
        val specificContact = message.contact
        if (specificContact != null) {
            try {
                BiliBiliBot.logger.info("消息指定了联系人: $specificContact")
                val contact = parsePlatformContact(specificContact) ?: return
                if (!shouldSendToContact(message, specificContact)) {
                    BiliBiliBot.logger.info("过滤器已拦截发送到 $specificContact")
                    return
                }
                val segments = buildMessageSegments(message, specificContact, dynamicSub, messageIdentity)
                val finalSegments = applyAtAllIfNeeded(contact, specificContact, message, segments)
                messageQueue.send(contact to finalSegments)
            } finally {
                TemplateSelectionService.clearBatchSelections(messageIdentity)
            }
            return
        }

        try {
            // 发送给所有订阅该用户的联系人
            for (contactStr in contacts) {
                try {
                    BiliBiliBot.logger.info("处理联系人: $contactStr")
                    val contact = parsePlatformContact(contactStr) ?: continue

                    // 检查是否被禁用
                    if (banList.contains(contactStr)) {
                        BiliBiliBot.logger.debug("联系人 $contactStr 已禁用推送")
                        continue
                    }

                    if (!shouldSendToContact(message, contactStr)) {
                        BiliBiliBot.logger.debug("过滤器已拦截发送到 $contactStr")
                        continue
                    }

                    // 发送链路先解析模板策略，再把模板正文交给渲染层，避免渲染层继续读取旧绑定结构。
                    val segments = buildMessageSegments(message, contactStr, dynamicSub, messageIdentity)
                    BiliBiliBot.logger.info("为联系人 $contactStr 构建了 ${segments.size} 个消息段")

                    val finalSegments = applyAtAllIfNeeded(contact, contactStr, message, segments)

                    // 加入发送队列
                    messageQueue.send(contact to finalSegments)
                    BiliBiliBot.logger.info("消息已加入发送队列: {}", contact.toSubject())

                    // 消息间隔
                    delay(BiliConfigManager.config.pushConfig.messageInterval)

                } catch (e: Exception) {
                    BiliBiliBot.logger.error("处理联系人 $contactStr 时出错: ${e.message}", e)
                }
            }
        } finally {
            TemplateSelectionService.clearBatchSelections(messageIdentity)
        }
    }

    /**
     * 在群消息满足条件时自动注入 @全体。
     *
     * @param contact 平台联系人
     * @param contactStr 联系人字符串表示
     * @param message 待发送消息
     * @param segments 原始消息段
     */
    private suspend fun applyAtAllIfNeeded(
        contact: PlatformContact,
        contactStr: String,
        message: BiliMessage,
        segments: List<OutgoingPart>
    ): List<OutgoingPart> {
        if (contact.type != PlatformChatType.GROUP) return segments
        val alreadyAtAll = segments.any { it.type == "at" && it.data["qq"] == "all" }
        if (alreadyAtAll) return segments
        val atAllEnabled = AtAllService.shouldAtAll(contactStr, message.mid, message)
        if (!atAllEnabled) return segments

        val atAllGuard = runCatching {
            PlatformCapabilityService.guardAtAllInContact(contact)
        }.getOrElse {
            BiliBiliBot.logger.warn("检查群 {} 的 @全体 权限失败: {}", contact.id, it.message)
            CapabilityGuard.unsupported("at-all guard failed: ${it.message}")
        }

        if (atAllGuard.stopCurrentOperation) {
            BiliBiliBot.logger.warn(
                "{}: 停止当前群 {} 的 @全体 注入分支",
                atAllGuard.marker ?: CapabilityGuard.UNSUPPORTED_MESSAGE,
                contact.id,
            )
            // 权限不足时只降级当前增强分支，保留普通推送可避免错过实际通知。
            notifyAtAllPermissionMissing(contact.id, message.mid)
            return segments
        }

        return listOf(OutgoingPart.atAll()) + segments
    }

    /**
     * 节流发送缺失 @全体 权限的管理员提醒。
     *
     * @param groupId 群 ID
     * @param uid 触发该提醒的订阅 UID
     */
    private suspend fun notifyAtAllPermissionMissing(groupId: String, uid: Long) {
        val now = System.currentTimeMillis()
        val groupKey = groupId
        if (shouldThrottleAtAllWarn(groupKey, now)) return

        val notice = "群 $groupId 已配置 At全体(UID: $uid)，但 Bot 无 @全体 权限，已自动降级为普通推送。请将 Bot 设为管理员。"
        runCatching { actionNotify(notice) }
            .onFailure { BiliBiliBot.logger.warn("发送 @全体 降级提醒失败: ${it.message}") }
    }

    /**
     * 节流发送 @全体 降级重试提醒。
     *
     * @param groupId 群 ID
     */
    private suspend fun notifyAtAllFallback(groupId: String) {
        val now = System.currentTimeMillis()
        val groupKey = groupId
        if (shouldThrottleAtAllWarn(groupKey, now)) return

        val notice = "群 $groupId 的 @全体 推送发送失败，已自动降级为普通推送。请检查 Bot 是否具备 @全体 权限或当日次数是否耗尽。"
        runCatching { actionNotify(notice) }
            .onFailure { BiliBiliBot.logger.warn("发送 @全体 重试降级提醒失败: ${it.message}") }
    }

    /**
     * 在单次检查中同时执行节流判定与缓存清理，保证提醒频控与内存占用都可控。
     */
    private fun shouldThrottleAtAllWarn(groupKey: String, now: Long): Boolean {
        return synchronized(atAllPermissionWarnTs) {
            cleanupAtAllWarnCache(now)
            val last = atAllPermissionWarnTs[groupKey]
            if (last != null && now - last < AT_ALL_WARN_INTERVAL_MS) {
                true
            } else {
                atAllPermissionWarnTs[groupKey] = now
                false
            }
        }
    }

    /**
     * 定期淘汰过期分组并裁剪上限，避免节流表在 7x24 运行中持续增长。
     */
    private fun cleanupAtAllWarnCache(now: Long) {
        if (now - lastAtAllWarnSweepTs < AT_ALL_WARN_SWEEP_INTERVAL_MS && atAllPermissionWarnTs.size <= AT_ALL_WARN_CACHE_MAX_SIZE) {
            return
        }
        lastAtAllWarnSweepTs = now

        val expireBefore = now - AT_ALL_WARN_RETAIN_MS
        atAllPermissionWarnTs.entries.removeIf { (_, timestamp) -> timestamp < expireBefore }

        if (atAllPermissionWarnTs.size <= AT_ALL_WARN_CACHE_MAX_SIZE) {
            return
        }

        val overflow = atAllPermissionWarnTs.size - AT_ALL_WARN_CACHE_MAX_SIZE
        val keysToEvict = atAllPermissionWarnTs.entries
            .sortedBy { it.value }
            .take(overflow)
            .map { it.key }
        keysToEvict.forEach { staleKey ->
            atAllPermissionWarnTs.remove(staleKey)
        }
    }

    /**
     * 判断消息段中是否包含 @全体。
     */
    private fun containsAtAllSegment(segments: List<OutgoingPart>): Boolean {
        return segments.any { it is OutgoingPart.MentionAll }
    }

    /**
     * 判断消息是否应该发送到指定联系人。
     *
     * @param message 待发送消息
     * @param contactStr 联系人字符串表示
     */
    private fun shouldSendToContact(message: BiliMessage, contactStr: String): Boolean {
        return when (message) {
            is DynamicMessage -> shouldSendDynamicToContact(message, contactStr)
            else -> true
        }
    }

    /**
     * 根据联系人过滤配置判断动态消息是否应被发送。
     *
     * @param message 动态消息
     * @param contactStr 联系人字符串表示
     */
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

    /**
     * 获取指定联系人对目标订阅项的动态过滤器。
     *
     * @param contactStr 联系人字符串表示
     * @param mid 订阅项标识
     */
    private fun getDynamicFilter(contactStr: String, mid: Long): DynamicFilter? {
        val byContact = BiliData.filter[contactStr] ?: return null
        return byContact[mid] ?: byContact[0L]
    }

    /**
     * 判断动态类型是否通过过滤。
     *
     * @param type 动态类型
     * @param filter 过滤规则
     */
    private fun passesTypeFilter(type: DynamicType, filter: DynamicFilter): Boolean {
        val typeSelect = filter.typeSelect
        if (typeSelect.list.isEmpty()) return true

        val mappedType = mapDynamicType(type)
        return when (typeSelect.mode) {
            FilterMode.WHITE_LIST -> mappedType in typeSelect.list
            FilterMode.BLACK_LIST -> mappedType !in typeSelect.list
        }
    }

    /**
     * 判断动态正文是否通过正则过滤。
     *
     * @param content 动态正文
     * @param filter 过滤规则
     */
    private fun passesRegularFilter(content: String, filter: DynamicFilter): Boolean {
        val regularSelect = filter.regularSelect
        if (regularSelect.list.isEmpty()) return true

        val text = content.ifEmpty { "" }
        return when (regularSelect.mode) {
            FilterMode.WHITE_LIST -> {
                regularSelect.list.all { pattern ->
                    val regex = runCatching { Regex(pattern) }.getOrNull()
                    if (regex == null) {
                        // 白名单配置写错时默认放行，避免错误规则把所有消息都误杀。
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
                        // 黑名单配置写错时默认不拦截，避免单条坏规则导致整组推送不可用。
                        BiliBiliBot.logger.warn("忽略非法正则: $pattern")
                        false
                    } else {
                        regex.containsMatchIn(text)
                    }
                }
            }
        }
    }

    /**
     * 将运行时动态类型映射为过滤器使用的类型枚举。
     *
     * @param type 动态类型
     */
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
     * 复用发送链路中的模板渲染与能力检查逻辑做启动预热，不实际投递到平台侧。
     *
     * @param message 预热消息
     * @param contactStr 目标联系人
     * @return 预热后得到的最终消息段
     */
    suspend fun warmupMessageBuildPath(
        message: BiliMessage,
        contactStr: String,
    ): List<OutgoingPart> {
        val segments = buildMessageSegments(
            message = message,
            contactStr = contactStr,
            subData = BiliData.dynamic[message.mid],
            messageIdentity = buildMessageIdentity(message),
        )
        val contact = parsePlatformContact(contactStr) ?: return segments
        // 预热阶段仅覆盖“发送前处理”路径，不触发实际发送。
        return applyAtAllIfNeeded(contact, contactStr, message, segments)
    }

    /**
     * 构建 OneBot v11 消息段
     */
    private suspend fun buildMessageSegments(
        message: BiliMessage,
        contactStr: String,
        subData: SubData?,
        messageIdentity: String,
    ): List<OutgoingPart> {
        val normalizedContact = normalizeContactSubject(contactStr) ?: contactStr
        val selectedTemplate = selectTemplateForContact(message, normalizedContact, subData, messageIdentity)
        // 消息模板渲染统一下沉到服务层，发送链路只负责确定“用哪个模板正文”。
        return TemplateRenderService.buildSegments(message, normalizedContact, selectedTemplate.templateContent)
    }

    /**
     * 根据联系人命中的 direct / groupRef 作用域选择模板。
     * 同一联系人可能同时属于多个 groupRef，这里把候选范围交给选择服务统一处理。
     */
    private fun selectTemplateForContact(
        message: BiliMessage,
        normalizedContact: String,
        subData: SubData?,
        messageIdentity: String,
    ): SelectedTemplate {
        val groupScopes = subData?.sourceRefs
            ?.mapNotNull { sourceRef -> parseGroupScopeForContact(sourceRef, normalizedContact) }
            ?.distinct()
            .orEmpty()
        return TemplateSelectionService.selectTemplate(
            type = messageType(message),
            uid = message.mid,
            directScope = "contact:$normalizedContact",
            groupScopes = groupScopes,
            messageIdentity = messageIdentity,
        )
    }

    /**
     * 解析当前联系人是否命中某个 groupRef 来源。
     * 只有当联系人确实属于该分组时，才将其视为模板候选 scope。
     */
    private fun parseGroupScopeForContact(sourceRef: String, normalizedContact: String): String? {
        if (!sourceRef.startsWith("groupRef:")) return null
        val groupName = sourceRef.removePrefix("groupRef:").takeIf { it.isNotBlank() } ?: return null
        val matched = BiliData.group[groupName]?.contacts?.any { subject ->
            subjectsEquivalent(subject, normalizedContact)
        } == true
        return if (matched) "groupRef:$groupName" else null
    }

    /**
     * 将消息模型映射为模板策略类型。
     * 选择服务和渲染层共享这一口径，避免同一消息在不同链路命中不同模板池。
     */
    private fun messageType(message: BiliMessage): String {
        return when (message) {
            is DynamicMessage -> "dynamic"
            is LiveMessage -> "live"
            is LiveCloseMessage -> "liveClose"
        }
    }

    /**
     * 为单条消息构造稳定的批次标识。
     * 该标识用于同分组同批次复用随机模板，并在批次结束后清理临时缓存。
     */
    private fun buildMessageIdentity(message: BiliMessage): String {
        return when (message) {
            is DynamicMessage -> "dynamic:${message.did}"
            is LiveMessage -> "live:${message.rid}:${message.timestamp}"
            is LiveCloseMessage -> "liveClose:${message.rid}:${message.timestamp}"
        }
    }
}

