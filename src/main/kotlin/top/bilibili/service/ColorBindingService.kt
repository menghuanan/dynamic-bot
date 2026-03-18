package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    fun resolveColorSource(uid: Long, subject: String?): ResolvedColorSource {
        val defaultColor = defaultGradientColorValue()
        val binding = colorOf(uid, subject)
            ?: return ResolvedColorSource(
                sourceType = ResolvedColorSourceType.DEFAULT_COLOR_FALLBACK,
                color = defaultColor,
                fallbackReason = ColorFallbackReason.NO_BINDING,
            )
        val normalized = normalizeSubjectScopedGradientColor(binding, NormalizationContext.USER_COMMAND)
            ?: return ResolvedColorSource(
                sourceType = ResolvedColorSourceType.DEFAULT_COLOR_FALLBACK,
                color = defaultColor,
                fallbackReason = ColorFallbackReason.BINDING_PARSE_FAILED,
            )
        return ResolvedColorSource(
            sourceType = ResolvedColorSourceType.SUBJECT_BOUND_NORMALIZED,
            color = normalized.normalizedColor,
        )
    }

    fun resolveColor(uid: Long, subject: String?): String {
        return resolveColorSource(uid, subject).color
    }

    suspend fun setColor(uid: Long, subject: String, color: String): ColorBindingResult = colorBindingMutex.withLock {
        BiliData.dynamic[uid] ?: return@withLock ColorBindingResult.failure("没有订阅该 UID: $uid")
        val normalizedSubject = normalizeColorBindingSubject(subject)
            ?: return@withLock ColorBindingResult.failure("联系人格式错误: $subject")
        val normalization = normalizeSubjectScopedGradientColor(color, NormalizationContext.USER_COMMAND)
            ?: return@withLock ColorBindingResult.failure(
                "格式错误，请输入16进制颜色，如: #d3edfa 或 #d3edfa;#fde8ed，最多支持 ${MAX_GRADIENT_COLOR_STOPS} 个颜色",
            )

        BiliData.dynamicColorByUid.getOrPut(normalizedSubject) { mutableMapOf() }[uid] = normalization.normalizedColor
        runCatching {
            DrawCacheMaintenanceService.clearSubjectScopedDrawCaches(uid, normalizedSubject)
        }.onFailure {
            BiliBiliBot.logger.warn("Failed to clear scoped draw cache for uid=$uid, subject=$normalizedSubject", it)
        }

        ColorBindingResult(
            success = true,
            message = buildColorBindingMessage(color, normalization.normalizedColor),
            normalizedColor = normalization.normalizedColor,
            changed = normalization.changed,
        )
    }

    private fun buildColorBindingMessage(rawInput: String, normalizedColor: String): String {
        return if (rawInput != normalizedColor) {
            "设置完成，已规范化: $rawInput -> $normalizedColor"
        } else {
            "设置完成，当前主题色: $normalizedColor"
        }
    }
}

fun DynamicService.colorOf(uid: Long, subject: String?): String? = ColorBindingService.colorOf(uid, subject)

fun DynamicService.resolveColorSource(uid: Long, subject: String?): ResolvedColorSource {
    return ColorBindingService.resolveColorSource(uid, subject)
}

fun DynamicService.resolveColor(uid: Long, subject: String?): String = ColorBindingService.resolveColor(uid, subject)

suspend fun DynamicService.setColor(uid: Long, subject: String, color: String): ColorBindingResult {
    return ColorBindingService.setColor(uid, subject, color)
}
