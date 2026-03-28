package top.bilibili.service

import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType

/**
 * 抽象统一消息发送能力，让上层代码不再区分具体平台适配器。
 */
interface MessageGateway {
    /**
     * 统一的平台联系人发送入口；新逻辑应优先使用它。
     */
    suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean

    /**
     * 统一走 capability guard 的发送入口；guard 阻断时只停止当前发送路径。
     */
    suspend fun sendMessageGuarded(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return sendMessage(contact, message)
    }

    /**
     * 为仍在迁移中的旧版数字群联系人调用方保留兼容入口。
     */
    @Deprecated(
        message = "优先使用 sendMessage(contact, message) 统一走 PlatformContact 发送入口",
        replaceWith = ReplaceWith("sendMessageGuarded(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()), message)"),
    )
    suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean {
        return sendMessageGuarded(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, groupId.toString()),
            message,
        )
    }

    /**
     * 为仍在迁移中的旧版数字私聊联系人调用方保留兼容入口。
     */
    @Deprecated(
        message = "优先使用 sendMessage(contact, message) 统一走 PlatformContact 发送入口",
        replaceWith = ReplaceWith("sendMessageGuarded(PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, userId.toString()), message)"),
    )
    suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean {
        return sendMessageGuarded(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, userId.toString()),
            message,
        )
    }

    /**
     * 为管理员通知提供统一入口，避免业务代码自行解析管理员联系人。
     */
    suspend fun sendAdminMessage(message: String): Boolean
}
