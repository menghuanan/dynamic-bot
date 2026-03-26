package top.bilibili.service

import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.toSubject

/**
 * 统一通过平台联系人发送纯文本，避免业务层继续区分群聊/私聊发送分支。
 */
suspend fun sendText(contact: PlatformContact, text: String): Boolean {
    return MessageGatewayProvider.require().sendMessage(contact, listOf(OutgoingPart.text(text)))
}

/**
 * 统一通过平台联系人发送消息片段集合。
 */
suspend fun sendParts(contact: PlatformContact, parts: List<OutgoingPart>): Boolean {
    return MessageGatewayProvider.require().sendMessage(contact, parts)
}

/**
 * 为当前会话生成标准化 subject，供订阅/模板/过滤器等持久化逻辑复用。
 */
fun currentSubject(contact: PlatformContact): String = contact.toSubject()
