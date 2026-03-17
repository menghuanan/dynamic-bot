package top.bilibili.service

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import top.bilibili.utils.parseContactId

object DrawCacheKeyService {
    private val liveTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun dynamicPath(uid: Long, dynamicId: String, subject: String?, color: String): String {
        val scopeDirectory = subjectScopeDirectory(subject)
        return "$uid/$scopeDirectory/${dynamicId}_${colorHash(color)}.png"
    }

    fun livePath(uid: Long, liveTime: Long, subject: String?, color: String): String {
        val scopeDirectory = subjectScopeDirectory(subject)
        val timeKey = formatLiveTime(liveTime)
        return "$uid/$scopeDirectory/${timeKey}_${colorHash(color)}.png"
    }

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