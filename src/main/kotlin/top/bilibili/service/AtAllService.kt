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
import top.bilibili.connector.PlatformChatType
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parsePlatformContact

/**
 * 集中维护按会话和 UID 生效的 @全体 策略，避免推送路径直接操作原始存储。
 */
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

    /**
     * 统一暴露 @全体 类型词判断，避免命令解析和策略写入维护两份映射表。
     */
    internal fun supportsType(type: String): Boolean = toAtAllType(type) != null

    /**
     * 写入 @全体 策略时顺带做联系人和 UID 范围校验，避免保存无效配置。
     */
    suspend fun addAtAll(type: String, uid: Long = 0L, subject: String): String = mutex.withLock {
        val atAllType = toAtAllType(type) ?: return@withLock "没有这个类型哦 [$type]"
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        val contact = parsePlatformContact(normalizedSubject) ?: return@withLock "联系人格式错误: $subject"
        if (contact.type != PlatformChatType.GROUP) return@withLock "仅群聊支持 @全体 策略"
        validateUidScope(uid, normalizedSubject)?.let { return@withLock it }

        val list = BiliData.atAll
            .getOrPut(normalizedSubject) { mutableMapOf() }
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

    /**
     * 删除指定作用域下的 @全体 策略，并在空桶时回收冗余节点。
     */
    suspend fun delAtAll(type: String, uid: Long = 0L, subject: String): String = mutex.withLock {
        val atAllType = toAtAllType(type) ?: return@withLock "没有这个类型哦 [$type]"
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        validateUidScope(uid, normalizedSubject)?.let { return@withLock it }

        val subjectMap = BiliData.atAll[normalizedSubject] ?: return@withLock "删除失败"
        val uidMap = subjectMap[uid] ?: return@withLock "删除失败"
        val removed = uidMap.remove(atAllType)
        if (!removed) return@withLock "删除失败"

        if (uidMap.isEmpty()) {
            subjectMap.remove(uid)
        }
        if (subjectMap.isEmpty()) {
            BiliData.atAll.remove(normalizedSubject)
        }
        "删除成功"
    }

    /**
     * 按会话或 UID 汇总 @全体 策略，方便命令层直接回显当前配置。
     */
    suspend fun listAtAll(uid: Long = 0L, subject: String): String = mutex.withLock {
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        if (uid == 0L) {
            val all = BiliData.atAll[normalizedSubject]
            if (all.isNullOrEmpty()) return@withLock "没有At全体项哦"
            return@withLock buildString {
                all.toSortedMap().forEach { (scopeUid, items) ->
                    appendLine("UID($scopeUid): ${items.joinToString(",") { it.value }}")
                }
            }.trim()
        }

        validateUidScope(uid, normalizedSubject)?.let { return@withLock it }
        val list = BiliData.atAll[normalizedSubject]?.get(uid)
        if (list.isNullOrEmpty()) return@withLock "没有At全体项哦"
        buildString { list.forEach { appendLine(it.value) } }.trim()
    }

    /**
     * 在推送前统一判断是否需要 @全体，避免消息链路各自重复实现类型映射逻辑。
     */
    suspend fun shouldAtAll(subject: String, uid: Long, message: BiliMessage): Boolean = mutex.withLock {
        val normalizedSubject = normalizeContactSubject(subject) ?: return@withLock false
        val list = BiliData.atAll[normalizedSubject]?.get(uid) ?: return@withLock false
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
