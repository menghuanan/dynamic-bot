package top.bilibili.utils

import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType
import top.bilibili.core.ContactId

const val ONEBOT11_CONTACT_NAMESPACE = "onebot11"
const val QQ_OFFICIAL_CONTACT_NAMESPACE = "qq_official"

/**
 * 将旧版 [ContactId] 转换为带平台命名空间的 subject。
 */
fun ContactId.toNamespacedSubject(): String = "$ONEBOT11_CONTACT_NAMESPACE:$type:$id"

/**
 * 将旧版 [ContactId] 转换为历史短格式 subject。
 */
fun ContactId.toLegacySubject(): String = "$type:$id"

/**
 * 将运行时平台联系人统一格式化为持久化/权限检查可复用的 subject。
 */
fun PlatformContact.toSubject(): String = "${platform.toNamespace()}:${type.toSubjectType()}:$id"

/**
 * 生成用于日志与提示的联系人标签，避免业务层继续拼接 group/private 字符串。
 */
fun PlatformContact.toDisplayLabel(): String = when (type) {
    PlatformChatType.GROUP -> "群 $id"
    PlatformChatType.PRIVATE -> "用户 $id"
}

/**
 * 解析持久化 subject；运行期落盘数据只接受带命名空间的联系人格式。
 */
fun parsePlatformContact(subject: String?): PlatformContact? {
    val raw = subject?.trim().orEmpty()
    if (raw.isBlank()) return null
    return when {
        raw.startsWith("$ONEBOT11_CONTACT_NAMESPACE:group:") ->
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, raw.removePrefix("$ONEBOT11_CONTACT_NAMESPACE:group:"))
        raw.startsWith("$ONEBOT11_CONTACT_NAMESPACE:private:") ->
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, raw.removePrefix("$ONEBOT11_CONTACT_NAMESPACE:private:"))
        raw.startsWith("$QQ_OFFICIAL_CONTACT_NAMESPACE:group:") ->
            PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP, raw.removePrefix("$QQ_OFFICIAL_CONTACT_NAMESPACE:group:"))
        raw.startsWith("$QQ_OFFICIAL_CONTACT_NAMESPACE:private:") ->
            PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, raw.removePrefix("$QQ_OFFICIAL_CONTACT_NAMESPACE:private:"))
        else -> null
    }
}

/**
 * 为命令参数解析联系人；未显式带命名空间时，沿用当前平台和预期联系人类型。
 */
fun parseCommandPlatformContact(
    raw: String?,
    defaultPlatform: PlatformType,
    defaultType: PlatformChatType,
): PlatformContact? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    return parsePlatformContact(text)
        ?: parseLegacyPlatformContact(text)
        ?: PlatformContact(defaultPlatform, defaultType, text)
}

/**
 * 将新旧格式的联系人 subject 统一归一化为带命名空间的新格式。
 */
fun normalizeContactSubject(subject: String?): String? {
    val contact = parsePlatformContact(subject) ?: parseLegacyPlatformContact(subject) ?: return null
    return contact.toSubject()
}

/**
 * 尝试归一化联系人 subject；无法识别时保留原值。
 */
fun normalizeContactSubjectOrKeep(subject: String): String = normalizeContactSubject(subject) ?: subject

/**
 * 将联系人 subject 转换为兼容旧权限数据的历史表示。
 */
fun legacyContactSubject(subject: String?): String? {
    val contact = parsePlatformContact(subject) ?: return null
    return if (contact.platform == PlatformType.ONEBOT11) {
        "${contact.type.toSubjectType()}:${contact.id}"
    } else {
        contact.toSubject()
    }
}

/**
 * 判断两个联系人 subject 在新旧格式混用时是否指向同一联系人。
 */
fun subjectsEquivalent(left: String?, right: String?): Boolean {
    if (left == null || right == null) return false
    val normalizedLeft = normalizeContactSubject(left)
    val normalizedRight = normalizeContactSubject(right)
    return if (normalizedLeft != null && normalizedRight != null) {
        normalizedLeft == normalizedRight
    } else {
        left == right
    }
}

/**
 * 判断集合内是否存在与候选 subject 语义等价的联系人标识。
 */
fun containsEquivalentSubject(subjects: Iterable<String>, candidate: String): Boolean {
    return subjects.any { subjectsEquivalent(it, candidate) }
}

/**
 * 返回集合中与候选 subject 语义等价的原始键值。
 */
fun findEquivalentSubjectKey(subjects: Iterable<String>, candidate: String): String? {
    return subjects.firstOrNull { subjectsEquivalent(it, candidate) }
}

/**
 * 从群聊 subject 中提取 OneBot11 风格的数字群号。
 */
fun groupIdFromSubject(subject: String?): Long? {
    val contact = parsePlatformContact(subject) ?: return null
    return if (contact.type == PlatformChatType.GROUP) contact.id.toLongOrNull() else null
}

/**
 * 返回可展示的群目标标识；非数字平台也不会在这里被直接丢弃。
 */
fun groupLabelFromSubject(subject: String?): String? {
    val contact = parsePlatformContact(subject) ?: return null
    return if (contact.type == PlatformChatType.GROUP) contact.id else null
}

/**
 * 提供 OneBot11 兼容的数字群号访问，避免在平台无关代码里散落 `toLongOrNull()`。
 */
fun PlatformContact.oneBotGroupIdOrNull(): Long? {
    return if (platform == PlatformType.ONEBOT11 && type == PlatformChatType.GROUP) id.toLongOrNull() else null
}

/**
 * 提供 OneBot11 兼容的数字私聊 ID 访问，避免在平台无关代码里散落 `toLongOrNull()`。
 */
fun PlatformContact.oneBotPrivateIdOrNull(): Long? {
    return if (platform == PlatformType.ONEBOT11 && type == PlatformChatType.PRIVATE) id.toLongOrNull() else null
}

/**
 * 提供旧权限/群归属数据所需的数字型用户 ID；仅对 OneBot11 私聊联系人生效。
 */
fun PlatformContact.oneBotUserIdOrNull(): Long? = oneBotPrivateIdOrNull()

/**
 * 兼容命令输入和旧配置迁移的历史短格式解析；新持久化路径不得直接依赖该入口。
 */
private fun parseLegacyPlatformContact(subject: String?): PlatformContact? {
    val raw = subject?.trim().orEmpty()
    if (raw.isBlank()) return null
    return when {
        raw.startsWith("group:") ->
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, raw.removePrefix("group:"))
        raw.startsWith("private:") ->
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, raw.removePrefix("private:"))
        raw.startsWith("g") && raw.length > 1 && raw.substring(1).all(Char::isDigit) ->
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, raw.substring(1))
        raw.startsWith("u") && raw.length > 1 && raw.substring(1).all(Char::isDigit) ->
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, raw.substring(1))
        else -> null
    }
}

/**
 * 将平台枚举统一映射到 subject 命名空间。
 */
private fun PlatformType.toNamespace(): String = when (this) {
    PlatformType.ONEBOT11 -> ONEBOT11_CONTACT_NAMESPACE
    PlatformType.QQ_OFFICIAL -> QQ_OFFICIAL_CONTACT_NAMESPACE
}

/**
 * 将聊天类型统一映射到 subject 类型段。
 */
private fun PlatformChatType.toSubjectType(): String = when (this) {
    PlatformChatType.GROUP -> "group"
    PlatformChatType.PRIVATE -> "private"
}
