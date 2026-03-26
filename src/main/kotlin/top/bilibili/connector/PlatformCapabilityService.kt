package top.bilibili.connector

import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.service.FeatureSwitchService
import top.bilibili.utils.parsePlatformContact

object PlatformCapabilityService {
    /**
     * 统一按业务语义判断某联系人当前是否可发送消息，避免业务层继续用“可达性”表达发送能力。
     */
    suspend fun canSendMessageTo(contact: PlatformContact): Boolean {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.canSendMessage(contact)
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
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.canSendImages(contact, images)
    }

    /**
     * 统一按业务语义判断当前联系人是否支持回复消息。
     */
    suspend fun canReplyInContact(contact: PlatformContact): Boolean {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.canReply(contact)
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
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return false
        }
        return BiliBiliBot.platformAdapter.canAtAll(contact)
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
