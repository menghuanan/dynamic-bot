package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.utils.parseContactId

private val colorBindingMutex = Mutex()
private val hexColorRegex = Regex("^#[0-9A-Fa-f]{6}$")

private fun normalizeColorBindingSubject(subject: String?): String? {
    val contact = subject?.let(::parseContactId) ?: return null
    return when (contact.type) {
        "group" -> "group:${contact.id}"
        "private" -> "private:${contact.id}"
        else -> null
    }
}

private fun normalizeColorBindingInput(color: String): String? {
    val segments = color.split(";", "；").map { it.trim() }
    if (segments.isEmpty() || segments.any { it.isEmpty() }) return null
    if (segments.any { !hexColorRegex.matches(it) }) return null
    return segments.joinToString(";")
}

object ColorBindingService {
    fun colorOf(uid: Long, subject: String?): String? {
        val normalizedSubject = normalizeColorBindingSubject(subject) ?: return null
        return BiliData.dynamicColorByUid[normalizedSubject]?.get(uid)
    }

    fun resolveColor(uid: Long, subject: String?): String {
        val defaultColor = runCatching { BiliConfigManager.config.imageConfig.defaultColor }
            .getOrElse { BiliConfig().imageConfig.defaultColor }
        return colorOf(uid, subject) ?: defaultColor
    }

    suspend fun setColor(uid: Long, subject: String, color: String): String = colorBindingMutex.withLock {
        dynamic[uid] ?: return@withLock "没有订阅过 UID: $uid"
        val normalizedSubject = normalizeColorBindingSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        val normalizedColor = normalizeColorBindingInput(color)
            ?: return@withLock "格式错误，请输入16进制颜色，如: #d3edfa 或 #d3edfa;#fde8ed"
        BiliData.dynamicColorByUid.getOrPut(normalizedSubject) { mutableMapOf() }[uid] = normalizedColor
        "设置完成"
    }
}

fun DynamicService.colorOf(uid: Long, subject: String?): String? = ColorBindingService.colorOf(uid, subject)

fun DynamicService.resolveColor(uid: Long, subject: String?): String = ColorBindingService.resolveColor(uid, subject)

suspend fun DynamicService.setColor(uid: Long, subject: String, color: String): String {
    return ColorBindingService.setColor(uid, subject, color)
}