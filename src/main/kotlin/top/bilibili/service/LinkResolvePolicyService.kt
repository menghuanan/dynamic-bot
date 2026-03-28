package top.bilibili.service

/**
 * 描述一次链接解析策略的放行结果，便于调用方同时处理警告和已批准列表。
 */
data class LinkResolvePolicyDecision(
    val approvedLinks: List<ResolvedLinkInfo>,
    val shouldWarnTooManyRequests: Boolean
)

/**
 * 统一处理链接解析的冷却与限流策略，避免消息入口自行维护状态窗口。
 */
class LinkResolvePolicyService(
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private val linkCooldowns = mutableMapOf<String, Long>()
    private val userApprovals = mutableMapOf<String, ArrayDeque<Long>>()

    /**
     * 按群和用户维度执行去重、冷却与频率限制，统一决定本次允许解析的链接。
     */
    @Synchronized
    fun applyPolicy(groupKey: String, userKey: String, candidates: List<ResolvedLinkInfo>): LinkResolvePolicyDecision {
        val now = nowProvider()
        cleanupExpiredState(now)

        val approved = mutableListOf<ResolvedLinkInfo>()
        val userWindow = userApprovals.getOrPut(userKey) { ArrayDeque() }
        var shouldWarn = false

        for (candidate in candidates) {
            if (approved.size >= MAX_APPROVED_LINKS_PER_MESSAGE) {
                break
            }

            val cooldownKey = buildCooldownKey(groupKey, candidate)
            val lastApprovedAt = linkCooldowns[cooldownKey]
            if (lastApprovedAt != null && now - lastApprovedAt < LINK_COOLDOWN_MS) {
                continue
            }

            trimExpiredUserWindow(userWindow, now)
            if (userWindow.size >= MAX_USER_PARSES_PER_WINDOW) {
                shouldWarn = true
                break
            }

            userWindow.addLast(now)
            linkCooldowns[cooldownKey] = now
            approved += candidate
        }

        if (userWindow.isEmpty()) {
            userApprovals.remove(userKey)
        }

        return LinkResolvePolicyDecision(
            approvedLinks = approved,
            shouldWarnTooManyRequests = shouldWarn
        )
    }

    /**
     * 为旧测试与 OneBot11 调用方保留 Long 型入口。
     */
    fun applyPolicy(groupId: Long, userId: Long, candidates: List<ResolvedLinkInfo>): LinkResolvePolicyDecision {
        return applyPolicy(groupId.toString(), userId.toString(), candidates)
    }

    private fun cleanupExpiredState(now: Long) {
        val cooldownIterator = linkCooldowns.iterator()
        while (cooldownIterator.hasNext()) {
            val entry = cooldownIterator.next()
            if (now - entry.value >= LINK_COOLDOWN_MS) {
                cooldownIterator.remove()
            }
        }

        val userIterator = userApprovals.iterator()
        while (userIterator.hasNext()) {
            val entry = userIterator.next()
            trimExpiredUserWindow(entry.value, now)
            if (entry.value.isEmpty()) {
                userIterator.remove()
            }
        }
    }

    private fun trimExpiredUserWindow(window: ArrayDeque<Long>, now: Long) {
        while (window.isNotEmpty() && now - window.first() >= USER_WINDOW_MS) {
            window.removeFirst()
        }
    }

    private fun buildCooldownKey(groupKey: String, candidate: ResolvedLinkInfo): String {
        return "$groupKey:${candidate.type.stableName}:${candidate.id}"
    }

    companion object {
        private const val LINK_COOLDOWN_MS = 60_000L
        private const val USER_WINDOW_MS = 60_000L
        private const val MAX_USER_PARSES_PER_WINDOW = 3
        private const val MAX_APPROVED_LINKS_PER_MESSAGE = 3
    }
}
