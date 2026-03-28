package top.bilibili.service

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import top.bilibili.utils.parseContactId

/**
 * 统一生成绘图缓存键，避免不同业务场景各自拼接路径导致失效粒度不一致。
 */
object DrawCacheKeyService {
    private val liveTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /**
     * 为动态卡片生成带会话作用域与主题色维度的缓存路径。
     */
    fun dynamicPath(uid: Long, dynamicId: String, subject: String?, color: String): String {
        val scopeDirectory = subjectScopeDirectory(subject)
        return "$uid/$scopeDirectory/${dynamicId}_${colorHash(color)}.png"
    }

    /**
     * 为直播卡片生成带会话作用域与主题色维度的缓存路径。
     */
    fun livePath(uid: Long, liveTime: Long, subject: String?, color: String): String {
        val scopeDirectory = subjectScopeDirectory(subject)
        val timeKey = formatLiveTime(liveTime)
        return "$uid/$scopeDirectory/${timeKey}_${colorHash(color)}.png"
    }

    /**
     * 为搜索卡片生成稳定缓存路径，避免实体类型冲突导致复用错误。
     */
    fun searchPath(uid: Long, entityType: String, entityId: String, subject: String?, color: String): String {
        val scopeDirectory = subjectScopeDirectory(subject)
        val entityHash = md5("$entityType|$entityId").take(10)
        return "$uid/$scopeDirectory/${entityHash}_${colorHash(color)}.png"
    }

    internal fun subjectScopeDirectory(subject: String?): String {
        val normalizedSubject = normalizeSubject(subject)
        return "scope_${md5(normalizedSubject).take(10)}"
    }

    private fun formatLiveTime(epochSecond: Long): String {
        val offset = OffsetDateTime.now().offset
        return LocalDateTime.ofEpochSecond(epochSecond, 0, offset).format(liveTimeFormatter)
    }

    private fun normalizeSubject(subject: String?): String {
        val normalized = subject
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(::parseContactId)
            ?.let { "${it.type}:${it.id}" }
        return normalized ?: "global"
    }

    private fun normalizeColor(color: String): String {
        return normalizeGradientColorForCache(color)
    }

    private fun colorHash(color: String): String {
        return md5(normalizeColor(color)).take(10)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
