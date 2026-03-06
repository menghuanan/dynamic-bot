package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.AtAllType
import top.bilibili.BiliData
import top.bilibili.data.BiliMessage
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.utils.parseContactId

object AtAllService {
    private val mutex = Mutex()

    private fun toAtAllType(type: String) =
        when (type.lowercase()) {
            "全部", "all", "a" -> AtAllType.ALL
            "全部动态", "dynamic", "d" -> AtAllType.DYNAMIC
            "直播", "live", "l" -> AtAllType.LIVE
            "视频", "video", "v" -> AtAllType.VIDEO
            "音乐", "music", "m" -> AtAllType.MUSIC
            "专栏", "article" -> AtAllType.ARTICLE
            else -> null
        }

    suspend fun addAtAll(type: String, uid: Long = 0L, subject: String): String = mutex.withLock {
        val atAllType = toAtAllType(type) ?: return@withLock "没有这个类型哦 [$type]"
        val contact = parseContactId(subject) ?: return@withLock "联系人格式错误: $subject"
        if (contact.type != "group") return@withLock "仅群聊支持 @全体 策略"
        validateUidScope(uid, subject)?.let { return@withLock it }

        val list = BiliData.atAll
            .getOrPut(subject) { mutableMapOf() }
            .getOrPut(uid) { mutableSetOf() }

        if (list.isEmpty()) {
            list.add(atAllType)
            return@withLock "添加成功"
        }

        when (atAllType) {
            AtAllType.ALL -> {
                list.clear()
                list.add(atAllType)
            }
            AtAllType.DYNAMIC -> {
                list.removeAll(listOf(AtAllType.ALL, AtAllType.VIDEO, AtAllType.MUSIC, AtAllType.ARTICLE))
                list.add(atAllType)
            }
            AtAllType.LIVE -> {
                list.remove(AtAllType.ALL)
                list.add(atAllType)
            }
            else -> {
                list.remove(AtAllType.ALL)
                list.remove(AtAllType.DYNAMIC)
                list.add(atAllType)
            }
        }
        "添加成功"
    }

    suspend fun delAtAll(type: String, uid: Long = 0L, subject: String): String = mutex.withLock {
        val atAllType = toAtAllType(type) ?: return@withLock "没有这个类型哦 [$type]"
        validateUidScope(uid, subject)?.let { return@withLock it }

        val subjectMap = BiliData.atAll[subject] ?: return@withLock "删除失败"
        val uidMap = subjectMap[uid] ?: return@withLock "删除失败"
        val removed = uidMap.remove(atAllType)
        if (!removed) return@withLock "删除失败"

        if (uidMap.isEmpty()) {
            subjectMap.remove(uid)
        }
        if (subjectMap.isEmpty()) {
            BiliData.atAll.remove(subject)
        }
        "删除成功"
    }

    suspend fun listAtAll(uid: Long = 0L, subject: String): String = mutex.withLock {
        if (uid == 0L) {
            val all = BiliData.atAll[subject]
            if (all.isNullOrEmpty()) return@withLock "没有At全体项哦"
            return@withLock buildString {
                all.toSortedMap().forEach { (scopeUid, items) ->
                    appendLine("UID($scopeUid): ${items.joinToString(",") { it.value }}")
                }
            }.trim()
        }

        validateUidScope(uid, subject)?.let { return@withLock it }
        val list = BiliData.atAll[subject]?.get(uid)
        if (list.isNullOrEmpty()) return@withLock "没有At全体项哦"
        buildString { list.forEach { appendLine(it.value) } }.trim()
    }

    suspend fun shouldAtAll(subject: String, uid: Long, message: BiliMessage): Boolean = mutex.withLock {
        val list = BiliData.atAll[subject]?.get(uid) ?: return@withLock false
        if (list.isEmpty()) return@withLock false
        if (AtAllType.ALL in list) return@withLock true

        return@withLock when (message) {
            is DynamicMessage -> {
                AtAllType.DYNAMIC in list || mapDynamicTypeToAtAll(message.type) in list
            }
            is LiveMessage -> AtAllType.LIVE in list
            is LiveCloseMessage -> false
        }
    }

    private fun mapDynamicTypeToAtAll(type: DynamicType): AtAllType {
        return when (type) {
            DynamicType.DYNAMIC_TYPE_AV -> AtAllType.VIDEO
            DynamicType.DYNAMIC_TYPE_MUSIC -> AtAllType.MUSIC
            DynamicType.DYNAMIC_TYPE_ARTICLE -> AtAllType.ARTICLE
            else -> AtAllType.DYNAMIC
        }
    }

    private fun validateUidScope(uid: Long, subject: String): String? {
        if (uid <= 0L) return "请指定有效 UID（必须是正整数）"
        if (!isFollow(uid, subject)) return "该群未订阅 UID: $uid"
        return null
    }
}
