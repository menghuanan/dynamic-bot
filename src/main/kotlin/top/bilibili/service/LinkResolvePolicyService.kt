package top.bilibili.service

data class LinkResolvePolicyDecision(
    val approvedLinks: List<ResolvedLinkInfo>,
    val shouldWarnTooManyRequests: Boolean
)

class LinkResolvePolicyService(
    private val nowProvider: () -> Long = System::currentTimeMillis
) {
    private val linkCooldowns = mutableMapOf<String, Long>()
    private val userApprovals = mutableMapOf<Long, ArrayDeque<Long>>()

    @Synchronized
    fun applyPolicy(groupId: Long, userId: Long, candidates: List<ResolvedLinkInfo>): LinkResolvePolicyDecision {
        val now = nowProvider()
        cleanupExpiredState(now)

        val approved = mutableListOf<ResolvedLinkInfo>()
        val userWindow = userApprovals.getOrPut(userId) { ArrayDeque() }
        var shouldWarn = false

        for (candidate in candidates) {
            if (approved.size >= MAX_APPROVED_LINKS_PER_MESSAGE) {
                break
            }

            val cooldownKey = buildCooldownKey(groupId, candidate)
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
            userApprovals.remove(userId)
        }

        return LinkResolvePolicyDecision(
            approvedLinks = approved,
            shouldWarnTooManyRequests = shouldWarn
        )
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

    private fun buildCooldownKey(groupId: Long, candidate: ResolvedLinkInfo): String {
        return "$groupId:${candidate.type}:${candidate.id}"
    }

    companion object {
        private const val LINK_COOLDOWN_MS = 60_000L
        private const val USER_WINDOW_MS = 60_000L
        private const val MAX_USER_PARSES_PER_WINDOW = 3
        private const val MAX_APPROVED_LINKS_PER_MESSAGE = 3
    }
}
