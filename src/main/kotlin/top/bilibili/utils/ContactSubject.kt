package top.bilibili.utils

import top.bilibili.core.ContactId

const val ONEBOT11_CONTACT_NAMESPACE = "onebot11"

fun ContactId.toNamespacedSubject(): String = "$ONEBOT11_CONTACT_NAMESPACE:$type:$id"

fun ContactId.toLegacySubject(): String = "$type:$id"

fun normalizeContactSubject(subject: String?): String? {
    val contact = subject?.let(::parseContactId) ?: return null
    return contact.toNamespacedSubject()
}

fun normalizeContactSubjectOrKeep(subject: String): String = normalizeContactSubject(subject) ?: subject

fun legacyContactSubject(subject: String?): String? {
    val contact = subject?.let(::parseContactId) ?: return null
    return contact.toLegacySubject()
}

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

fun containsEquivalentSubject(subjects: Iterable<String>, candidate: String): Boolean {
    return subjects.any { subjectsEquivalent(it, candidate) }
}

fun findEquivalentSubjectKey(subjects: Iterable<String>, candidate: String): String? {
    return subjects.firstOrNull { subjectsEquivalent(it, candidate) }
}

fun groupIdFromSubject(subject: String?): Long? {
    val contact = subject?.let(::parseContactId) ?: return null
    return if (contact.type == "group") contact.id else null
}
