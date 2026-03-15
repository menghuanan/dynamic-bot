package top.bilibili.service

import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import top.bilibili.utils.parseContactId

object DrawCacheKeyService {
    private val liveTimeFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    fun dynamicPath(uid: Long, dynamicId: String, subject: String?, color: String): String {
        val suffix = scopeHash(subject, color)
        return "$uid/${dynamicId}_$suffix.png"
    }

    fun livePath(uid: Long, liveTime: Long, subject: String?, color: String): String {
        val suffix = scopeHash(subject, color)
        val timeKey = formatLiveTime(liveTime)
        return "$uid/${timeKey}_$suffix.png"
    }

    private fun formatLiveTime(epochSecond: Long): String {
        val offset = OffsetDateTime.now().offset
        return LocalDateTime.ofEpochSecond(epochSecond, 0, offset).format(liveTimeFormatter)
    }

    private fun scopeHash(subject: String?, color: String): String {
        val normalizedSubject = normalizeSubject(subject)
        val normalizedColor = normalizeColor(color)
        return md5("$normalizedSubject|$normalizedColor").take(10)
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
        return color
            .split(";", "；")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .joinToString(";")
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
}
