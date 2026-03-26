package top.bilibili.connector

import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.service.FeatureSwitchService
import top.bilibili.utils.parsePlatformContact

object PlatformCapabilityService {
    /**
     * 统一把 capability request 路由到平台适配器 guard；未初始化时直接返回 unsupported。
     */
    suspend fun guardCapability(request: CapabilityRequest): CapabilityGuardResult {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return CapabilityGuard.unsupported("platform adapter is not initialized")
        }
        return BiliBiliBot.platformAdapter.guardCapability(request)
    }

    /**
     * 统一判断基础发送能力，供发送链在真正执行前先收口 capability 判定。
     */
    suspend fun guardMessageSend(contact: PlatformContact): CapabilityGuardResult {
        return guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.SEND_MESSAGE,
                contact = contact,
            ),
        )
    }

    /**
     * 统一判断图片直发能力，后续发送链可按 Degraded/Unsupported 决定是否走 fallback。
     */
    suspend fun guardImageSend(
        contact: PlatformContact,
        images: List<ImageSource>,
    ): CapabilityGuardResult {
        return guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.SEND_IMAGES,
                contact = contact,
                images = images,
            ),
        )
    }

    /**
     * 统一判断回复能力，避免业务层继续直接调用 adapter 布尔接口。
     */
    suspend fun guardReplyInContact(contact: PlatformContact): CapabilityGuardResult {
        return guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.REPLY,
                contact = contact,
            ),
        )
    }

    /**
     * 统一判断 @全体 能力，后续命令链只消费 guard 结果而不是手写平台分支。
     */
    suspend fun guardAtAllInContact(contact: PlatformContact): CapabilityGuardResult {
        return guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.AT_ALL,
                contact = contact,
            ),
        )
    }

    /**
     * 统一按业务语义判断某联系人当前是否可发送消息，避免业务层继续用“可达性”表达发送能力。
     */
    suspend fun canSendMessageTo(contact: PlatformContact): Boolean {
        return guardMessageSend(contact) is CapabilityGuardResult.Supported
    }

    /**
     * 统一判断当前配置下是否允许主动发送管理通知，并校验管理员联系人是否真实可发送。
     */
    suspend fun canSendManagedAdminNotice(subject: String? = null): Boolean {
        if (!FeatureSwitchService.canSendManagedAdminNotice(subject = subject)) {
            return false
        }
        val adminContact = BiliConfigManager.config
            .normalizedAdminSubject()
            ?.let(::parsePlatformContact)
            ?: return false
        return canSendMessageTo(adminContact)
    }

    /**
     * 统一按业务语义判断当前联系人是否可直接发送指定图片集合。
     */
    suspend fun canSendImagesTo(contact: PlatformContact, images: List<ImageSource>): Boolean {
        return guardImageSend(contact, images) is CapabilityGuardResult.Supported
    }

    /**
     * 统一按业务语义判断当前联系人是否支持回复消息。
     */
    suspend fun canReplyInContact(contact: PlatformContact): Boolean {
        return guardReplyInContact(contact) is CapabilityGuardResult.Supported
    }

    /**
     * 兼容旧调用名，后续新逻辑应统一使用 canSendMessageTo。
     */
    @Deprecated(
        message = "使用 canSendMessageTo(contact) 统一表达发送能力",
        replaceWith = ReplaceWith("canSendMessageTo(contact)"),
    )
    suspend fun isContactReachable(contact: PlatformContact): Boolean = canSendMessageTo(contact)

    /**
     * 兼容旧的群号能力入口，后续新逻辑应优先传入 PlatformContact。
     */
    @Deprecated(
        message = "使用 canSendMessageTo(PlatformContact(...)) 统一表达发送能力",
        replaceWith = ReplaceWith("canSendMessageTo(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()))"),
    )
    suspend fun isGroupReachable(groupId: Long): Boolean {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.isGroupReachable(groupId)
    }

    /**
     * 统一按平台联系人判断是否支持 @全体。
     */
    suspend fun canAtAllInContact(contact: PlatformContact): Boolean {
        return guardAtAllInContact(contact) is CapabilityGuardResult.Supported
    }

    /**
     * 兼容旧的群号 @全体 判断入口，后续新逻辑应优先传入 PlatformContact。
     */
    @Deprecated(
        message = "使用 canAtAllInContact(contact) 统一表达联系人能力",
        replaceWith = ReplaceWith("canAtAllInContact(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()))"),
    )
    suspend fun canAtAllInGroup(groupId: Long): Boolean {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.canAtAll(groupId)
    }
}
