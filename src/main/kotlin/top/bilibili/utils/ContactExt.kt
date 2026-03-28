package top.bilibili.utils

import top.bilibili.connector.PlatformContact
import top.bilibili.core.ContactId

/**
 * 将联系人标识转换为适合展示的中文名称。
 */
fun ContactId.name(): String {
    return when (type) {
        "group" -> "群 $id"
        "private" -> "用户 $id"
        else -> "unknown $id"
    }
}

/**
 * 按项目既有序列化格式将字符串还原为 [ContactId]。
 */
fun String.toContactId(): ContactId {
    return ContactId.from(this)
}

/**
 * 安全解析联系人字符串，失败时返回 `null` 而不是抛异常。
 *
 * @param contactStr 联系人字符串，格式如 `"group_123456789"` 或 `"private_987654321"`
 * @return 解析得到的 [ContactId]，解析失败时返回 `null`
 */
fun parseContactId(contactStr: String): ContactId? {
    return try {
        ContactId.from(contactStr)
    } catch (e: Exception) {
        // 这里吞掉格式异常，是为了让命令解析流程继续走统一的“未匹配”分支。
        null
    }
}

/**
 * 解析运行时平台联系人。
 *
 * 该入口保留为兼容调用方的空值语义，底层逻辑仍统一委托给新的 subject 解析实现。
 */
fun parsePlatformContactOrNull(contactStr: String): PlatformContact? {
    return parsePlatformContact(contactStr)
}
