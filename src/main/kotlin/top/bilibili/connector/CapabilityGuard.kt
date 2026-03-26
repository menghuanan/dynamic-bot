package top.bilibili.connector

sealed interface CapabilityGuardResult {
    val stopCurrentOperation: Boolean
    val allowFallback: Boolean
    val marker: String?

    data object Supported : CapabilityGuardResult {
        override val stopCurrentOperation: Boolean = false
        override val allowFallback: Boolean = false
        override val marker: String? = null
    }

    data class Degraded(
        override val marker: String = CapabilityGuard.UNSUPPORTED_MESSAGE,
        val reason: String,
    ) : CapabilityGuardResult {
        override val stopCurrentOperation: Boolean = true
        override val allowFallback: Boolean = true
    }

    data class Unsupported(
        override val marker: String = CapabilityGuard.UNSUPPORTED_MESSAGE,
        val reason: String,
    ) : CapabilityGuardResult {
        override val stopCurrentOperation: Boolean = true
        override val allowFallback: Boolean = false
    }
}

object CapabilityGuard {
    const val UNSUPPORTED_MESSAGE: String = "没有适配此功能，请反馈"

    /**
     * 统一把显式 capability 声明与当前请求上下文的运行时判断合并成单一 guard 结果。
     */
    suspend fun evaluate(
        adapter: PlatformAdapter,
        request: CapabilityRequest,
    ): CapabilityGuardResult {
        if (!adapter.declaredCapabilities().contains(request.capability)) {
            return CapabilityGuardResult.Unsupported(reason = "${request.capability} is not declared")
        }
        return if (isAvailable(adapter, request)) {
            CapabilityGuardResult.Supported
        } else {
            CapabilityGuardResult.Degraded(reason = "${request.capability} is temporarily unavailable")
        }
    }

    /**
     * 为未初始化或未接入平台适配器的场景提供统一 unsupported 结果，保持日志标记一致。
     */
    fun unsupported(reason: String): CapabilityGuardResult.Unsupported {
        return CapabilityGuardResult.Unsupported(reason = reason)
    }

    private suspend fun isAvailable(
        adapter: PlatformAdapter,
        request: CapabilityRequest,
    ): Boolean {
        return when (request.capability) {
            PlatformCapability.SEND_MESSAGE -> request.contact?.let { contact ->
                adapter.canSendMessage(contact)
            } == true
            PlatformCapability.SEND_IMAGES -> request.contact?.let { contact ->
                adapter.canSendImages(contact, request.images)
            } == true
            PlatformCapability.REPLY -> request.contact?.let { contact ->
                adapter.canReply(contact)
            } == true
            PlatformCapability.AT_ALL -> request.contact?.let { contact ->
                adapter.canAtAll(contact)
            } == true
            PlatformCapability.LINK_RESOLVE -> true
        }
    }
}
