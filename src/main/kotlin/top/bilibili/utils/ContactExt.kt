package top.bilibili.utils

import top.bilibili.connector.PlatformContact
import top.bilibili.core.ContactId

/**
 * 获取联系人名称
 */
fun ContactId.name(): String {
    return when (type) {
        "group" -> "群 $id"
        "private" -> "用户 $id"
        else -> "unknown $id"
    }
}

/**
 * 从字符串解析 ContactId
 */
fun String.toContactId(): ContactId {
    return ContactId.from(this)
}

/**
 * 解析联系人字符串为 ContactId
 * @param contactStr 联系人字符串，格式: "group_123456789" 或 "private_987654321"
 * @return ContactId 或 null（如果解析失败）
 */
fun parseContactId(contactStr: String): ContactId? {
    return try {
        ContactId.from(contactStr)
    } catch (e: Exception) {
        null
    }
}

/**
 * 解析运行时平台联系人；兼容旧 OneBot11 subject 和新的多平台 subject。
 */
fun parsePlatformContactOrNull(contactStr: String): PlatformContact? {
    return parsePlatformContact(contactStr)
}
