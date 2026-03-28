package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliData
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.normalizeContactSubject

private val colorBindingMutex = Mutex()

private fun normalizeColorBindingSubject(subject: String?): String? {
    return normalizeContactSubject(subject)
}

/**
 * 集中管理会话作用域的主题色绑定，避免渲染路径直接读取原始数据结构。
 */
object ColorBindingService {
    /**
     * 按 UID 和会话读取主题色绑定，让上层无需感知存储键的归一化规则。
     */
    fun colorOf(uid: Long, subject: String?): String? {
        val normalizedSubject = normalizeColorBindingSubject(subject) ?: return null
        return BiliData.dynamicColorByUid[normalizedSubject]?.get(uid)
    }

    /**
     * 返回主题色及其来源，便于渲染链路区分绑定命中与默认回退。
     */
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

    /**
     * 为只关心最终颜色值的调用方提供简化入口。
     */
    fun resolveColor(uid: Long, subject: String?): String {
        return resolveColorSource(uid, subject).color
    }

    /**
     * 在写入主题色前统一做订阅校验、颜色归一化和绘图缓存失效处理。
     */
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
            BiliBiliBot.logger.warn("清理目标作用域绘图缓存失败: uid=$uid, subject=$normalizedSubject", it)
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

/**
 * 保留 DynamicService 侧的兼容委托入口，避免旧调用方直接感知主题色服务拆分。
 */
fun DynamicService.colorOf(uid: Long, subject: String?): String? = ColorBindingService.colorOf(uid, subject)

/**
 * 保留 DynamicService 侧的颜色来源查询入口，兼容旧调用链同时复用新实现。
 */
fun DynamicService.resolveColorSource(uid: Long, subject: String?): ResolvedColorSource {
    return ColorBindingService.resolveColorSource(uid, subject)
}

/**
 * 保留 DynamicService 侧的最终颜色查询入口，继续复用统一绑定解析逻辑。
 */
fun DynamicService.resolveColor(uid: Long, subject: String?): String = ColorBindingService.resolveColor(uid, subject)

/**
 * 保留 DynamicService 侧的设置入口，让历史调用方也能走统一校验与缓存清理流程。
 */
suspend fun DynamicService.setColor(uid: Long, subject: String, color: String): ColorBindingResult {
    return ColorBindingService.setColor(uid, subject, color)
}
