package top.bilibili.service

import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType

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

    suspend fun sendAdminMessage(message: String): Boolean
}
