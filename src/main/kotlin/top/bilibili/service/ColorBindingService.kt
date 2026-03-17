package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.parseContactId

private val colorBindingMutex = Mutex()

private fun normalizeColorBindingSubject(subject: String?): String? {
    val contact = subject?.let(::parseContactId) ?: return null
    return when (contact.type) {
        "group" -> "group:${contact.id}"
        "private" -> "private:${contact.id}"
        else -> null
    }
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
        BiliData.dynamic[uid] ?: return@withLock "没有订阅过 UID: $uid"
        val normalizedSubject = normalizeColorBindingSubject(subject)
            ?: return@withLock "联系人格式错误: $subject"
        val normalizedColor = normalizeGradientColorInput(color)
            ?: return@withLock "格式错误，请输入16进制颜色，如: #d3edfa 或 #d3edfa;#fde8ed，最多支持 ${MAX_GRADIENT_COLOR_STOPS} 个颜色"
        BiliData.dynamicColorByUid.getOrPut(normalizedSubject) { mutableMapOf() }[uid] = normalizedColor
        runCatching {
            DrawCacheMaintenanceService.clearSubjectScopedDrawCaches(uid, normalizedSubject)
        }.onFailure {
            BiliBiliBot.logger.warn("Failed to clear scoped draw cache for uid=$uid, subject=$normalizedSubject", it)
        }
        "设置完成"
    }
}

fun DynamicService.colorOf(uid: Long, subject: String?): String? = ColorBindingService.colorOf(uid, subject)

fun DynamicService.resolveColor(uid: Long, subject: String?): String = ColorBindingService.resolveColor(uid, subject)

suspend fun DynamicService.setColor(uid: Long, subject: String, color: String): String {
    return ColorBindingService.setColor(uid, subject, color)
}