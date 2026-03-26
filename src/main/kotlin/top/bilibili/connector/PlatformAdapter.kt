package top.bilibili.connector

import kotlinx.coroutines.flow.Flow

interface PlatformAdapter {
    val eventFlow: Flow<PlatformInboundMessage>

    fun start()

    fun stop()

    /**
     * 显式声明当前适配器实现支持的能力集合，供统一 guard 先做实现级筛选。
     */
    fun declaredCapabilities(): Set<PlatformCapability>

    /**
     * 统一返回请求级能力判断结果，避免业务层继续散落 capability 分支。
     */
    suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        return CapabilityGuard.evaluate(this, request)
    }

    /**
     * 统一的平台发送入口；业务层不再直接依赖 Long 型联系人。
     */
    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean

    /**
     * 按业务语义判断联系人当前是否具备“可发送消息”的条件，默认沿用可达性结果。
     */
    suspend fun canSendMessage(contact: PlatformContact): Boolean {
        return isContactReachable(contact)
    }

    /**
     * 按业务语义判断当前联系人是否可直接发送指定图片集合，默认沿用基础发送能力。
     */
    suspend fun canSendImages(contact: PlatformContact, images: List<ImageSource>): Boolean {
        return canSendMessage(contact)
    }

    /**
     * 按业务语义判断当前联系人是否支持回复消息，默认沿用基础发送能力。
     */
    suspend fun canReply(contact: PlatformContact): Boolean {
        return canSendMessage(contact)
    }

    /**
     * 为仍在迁移中的 OneBot11 调用方保留数字群号便捷入口。
     */
    @Deprecated(
        message = "优先使用 sendMessage(contact, message) 统一走 PlatformContact 发送入口",
        replaceWith = ReplaceWith("sendMessage(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()), message)"),
    )
    suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean {
        return sendMessage(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()),
            message,
        )
    }

    /**
     * 为仍在迁移中的 OneBot11 调用方保留数字私聊便捷入口。
     */
    @Deprecated(
        message = "优先使用 sendMessage(contact, message) 统一走 PlatformContact 发送入口",
        replaceWith = ReplaceWith("sendMessage(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, userId.toString()), message)"),
    )
    suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean {
        return sendMessage(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, userId.toString()),
            message,
        )
    }

    fun runtimeStatus(): PlatformRuntimeStatus

    /**
     * 统一判断联系人是否可达，供命令与推送逻辑做显式降级。
     */
    suspend fun isContactReachable(contact: PlatformContact): Boolean

    /**
     * 统一判断某联系人上下文是否支持 @全体。
     */
    suspend fun canAtAll(contact: PlatformContact): Boolean

    /**
     * 为仍在迁移中的 OneBot11 群能力判断保留兼容入口。
     */
    suspend fun isGroupReachable(groupId: Long): Boolean {
        return isContactReachable(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()),
        )
    }

    /**
     * 为仍在迁移中的 OneBot11 群能力判断保留兼容入口。
     */
    suspend fun canAtAll(groupId: Long): Boolean {
        return canAtAll(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()),
        )
    }
}
