package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliConfigManager
import top.bilibili.SubData
import top.bilibili.api.follow
import top.bilibili.api.groupAddUser
import top.bilibili.api.isFollow
import top.bilibili.api.unfollow
import top.bilibili.api.userInfo
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.actionNotify
import top.bilibili.utils.parseContactId

object DynamicService {
    private val mutex = Mutex()
    private val hexColorRegex = Regex("^#[0-9A-Fa-f]{6}$")

    // 容量保护
    private const val MAX_SUBSCRIPTIONS = 50000
    private const val MAX_CONTACTS_PER_UID = 1000

    // sourceRefs 统一订阅来源:
    // - direct:<contact>
    // - groupRef:<groupName>
    private const val DIRECT_PREFIX = "direct:"
    private const val GROUP_REF_PREFIX = "groupRef:"

    private suspend fun followUser(uid: Long): String? {
        if (uid == BiliBiliBot.uid) return null

        val attr = client.isFollow(uid)?.attribute
        if (attr == 0) {
            if (!BiliConfigManager.config.accountConfig.autoFollow) return "未关注此用户"
            val res = client.follow(uid)
            if (res.code != 0) return "关注失败: ${res.message}"
            if (BiliConfigManager.config.accountConfig.followGroup.isNotEmpty()) {
                val res1 = client.groupAddUser(uid, BiliBiliBot.tagid)
                if (res1.code != 0) logger.error("移动分组失败: ${res1.message}")
            }
            actionNotify("通知: 账号关注 $uid")
        } else if (attr == 128) {
            return "此账号已被拉黑"
        }
        return null
    }

    private fun normalizeSubject(subject: String): String? {
        val contact = parseContactId(subject) ?: return null
        return when (contact.type) {
            "group" -> "group:${contact.id}"
            "private" -> "private:${contact.id}"
            else -> null
        }
    }

    private fun directSourceRef(subject: String): String = "$DIRECT_PREFIX$subject"
    private fun groupSourceRef(groupName: String): String = "$GROUP_REF_PREFIX$groupName"

    private fun parseDirectSourceRef(sourceRef: String): String? {
        if (!sourceRef.startsWith(DIRECT_PREFIX)) return null
        return normalizeSubject(sourceRef.removePrefix(DIRECT_PREFIX))
    }

    private fun parseGroupSourceRef(sourceRef: String): String? {
        if (!sourceRef.startsWith(GROUP_REF_PREFIX)) return null
        return sourceRef.removePrefix(GROUP_REF_PREFIX).takeIf { it.isNotBlank() }
    }

    private fun rebuildContactsFromSources(subData: SubData): Set<String> {
        val normalizedSourceRefs = mutableSetOf<String>()
        val resolvedContacts = mutableSetOf<String>()

        subData.sourceRefs.toList().forEach { sourceRef ->
            val directContact = parseDirectSourceRef(sourceRef)
            if (directContact != null) {
                normalizedSourceRefs.add(directSourceRef(directContact))
                resolvedContacts.add(directContact)
                return@forEach
            }

            val groupName = parseGroupSourceRef(sourceRef)
            if (groupName != null) {
                normalizedSourceRefs.add(groupSourceRef(groupName))
                val contactsInGroup = group[groupName]?.contacts.orEmpty()
                contactsInGroup.mapNotNullTo(resolvedContacts) { normalizeSubject(it) }
                return@forEach
            }

            // 兼容历史脏数据: 直接将旧 contact 视为 direct 来源
            val normalizedLegacyContact = normalizeSubject(sourceRef)
            if (normalizedLegacyContact != null) {
                normalizedSourceRefs.add(directSourceRef(normalizedLegacyContact))
                resolvedContacts.add(normalizedLegacyContact)
            }
        }

        subData.sourceRefs.clear()
        subData.sourceRefs.addAll(normalizedSourceRefs)

        subData.contacts.clear()
        subData.contacts.addAll(resolvedContacts)
        return resolvedContacts
    }

    private fun cleanupRemovedContactFilters(uid: Long, removedContacts: Set<String>) {
        removedContacts.forEach { contact ->
            if (filter[contact]?.run {
                    remove(uid)
                    isEmpty()
                } == true
            ) {
                filter.remove(contact)
            }
        }
    }

    private suspend fun removeUidCompletely(uid: Long, contactsForCleanup: Set<String>) {
        dynamic.remove(uid)

        // 同步清理与 UID 绑定的数据
        filter.forEach { (_, filterMap) ->
            filterMap.remove(uid)
        }

        if (contactsForCleanup.isNotEmpty()) {
            top.bilibili.BiliData.dynamicPushTemplate.values.forEach { contacts ->
                contacts.removeAll(contactsForCleanup)
            }
            top.bilibili.BiliData.livePushTemplate.values.forEach { contacts ->
                contacts.removeAll(contactsForCleanup)
            }
            top.bilibili.BiliData.liveCloseTemplate.values.forEach { contacts ->
                contacts.removeAll(contactsForCleanup)
            }
        }

        logger.info("已完全移除 UID $uid 的订阅数据（无订阅来源）")
        unfollowUser(uid)
    }

    private suspend fun createSubData(uid: Long): Pair<SubData?, String?> {
        if (dynamic.size >= MAX_SUBSCRIPTIONS) {
            return null to "订阅数量已达上限 $MAX_SUBSCRIPTIONS，无法添加新订阅"
        }

        val userName = if (uid == BiliBiliBot.uid) {
            client.userInfo(uid)?.name
        } else {
            val followError = followUser(uid)
            if (followError != null) return null to followError
            if (uid == 11783021L) "哔哩哔哩番剧出差" else client.userInfo(uid)?.name
        }

        if (userName.isNullOrBlank()) {
            return null to "获取UP主信息失败，请稍后重试"
        }

        val subData = SubData(userName)
        dynamic[uid] = subData
        return subData to null
    }

    private suspend fun unfollowUser(uid: Long) {
        if (uid == BiliBiliBot.uid) return

        val hasOtherSubscribers = dynamic[uid]?.contacts?.isNotEmpty() == true
        if (hasOtherSubscribers || !BiliConfigManager.config.accountConfig.autoFollow) return

        val attr = client.isFollow(uid)?.attribute
        if (attr != null && attr != 0 && attr != 128) {
            val res = client.unfollow(uid)
            if (res.code == 0) {
                actionNotify("通知: 账号取消关注 $uid")
            } else {
                logger.error("取消关注失败: ${res.message}")
            }
        }
    }

    private fun normalizeColorInput(color: String): String? {
        val segments = color.split(";", "；").map { it.trim() }
        if (segments.isEmpty() || segments.any { it.isEmpty() }) return null
        if (segments.any { !hexColorRegex.matches(it) }) return null
        return segments.joinToString(";")
    }

    suspend fun setColor(uid: Long, color: String) = mutex.withLock {
        val subData = dynamic[uid] ?: return@withLock "没有订阅过 UID: $uid"
        val normalized = normalizeColorInput(color)
            ?: return@withLock "格式错误，请输入16进制颜色，如: #d3edfa 或 #d3edfa;#fde8ed"
        subData.color = normalized
        "设置完成"
    }

    suspend fun addDirectSubscribe(uid: Long, subject: String, isSelf: Boolean = true) = mutex.withLock {
        val normalizedSubject = normalizeSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        val sourceRef = directSourceRef(normalizedSubject)

        val existing = dynamic[uid]
        if (existing != null && sourceRef in existing.sourceRefs) return@withLock "之前订阅过这个人哦"

        val subData = existing ?: run {
            val (created, err) = createSubData(uid)
            if (err != null) return@withLock err
            created!!
        }

        val oldContacts = subData.contacts.toSet()
        subData.sourceRefs.add(sourceRef)
        rebuildContactsFromSources(subData)

        if (subData.contacts.size > MAX_CONTACTS_PER_UID) {
            subData.sourceRefs.remove(sourceRef)
            rebuildContactsFromSources(subData)
            if (subData.sourceRefs.isEmpty()) {
                dynamic.remove(uid)
            }
            return@withLock "UID $uid 的订阅联系人数量已达上限 $MAX_CONTACTS_PER_UID"
        }

        cleanupRemovedContactFilters(uid, oldContacts - subData.contacts)

        if (isSelf) "订阅 ${subData.name} 成功!"
        else "为 $normalizedSubject 订阅 ${subData.name} 成功!"
    }

    suspend fun removeDirectSubscribe(uid: Long, subject: String, isSelf: Boolean = true) = mutex.withLock {
        val normalizedSubject = normalizeSubject(subject) ?: return@withLock "联系人格式错误: $subject"
        val user = dynamic[uid] ?: return@withLock "还未订阅此人哦"

        val sourceRef = directSourceRef(normalizedSubject)
        if (sourceRef !in user.sourceRefs) return@withLock "还未订阅此人哦"

        val oldContacts = user.contacts.toSet()
        user.sourceRefs.remove(sourceRef)
        rebuildContactsFromSources(user)
        cleanupRemovedContactFilters(uid, oldContacts - user.contacts)

        if (user.sourceRefs.isEmpty()) {
            removeUidCompletely(uid, oldContacts)
        }

        if (isSelf) "取消订阅 ${user.name} 成功"
        else "为 $normalizedSubject 取消订阅 ${user.name} 成功"
    }

    suspend fun addGroupSubscribe(uid: Long, groupName: String) = mutex.withLock {
        val targetGroup = group[groupName] ?: return@withLock "分组 $groupName 不存在"
        if (targetGroup.contacts.isEmpty()) return@withLock "分组 $groupName 中没有任何联系人"

        val sourceRef = groupSourceRef(groupName)
        val existing = dynamic[uid]
        if (existing != null && sourceRef in existing.sourceRefs) return@withLock "分组 $groupName 之前订阅过这个人哦"

        val subData = existing ?: run {
            val (created, err) = createSubData(uid)
            if (err != null) return@withLock err
            created!!
        }

        val oldContacts = subData.contacts.toSet()
        subData.sourceRefs.add(sourceRef)
        rebuildContactsFromSources(subData)

        if (subData.contacts.size > MAX_CONTACTS_PER_UID) {
            subData.sourceRefs.remove(sourceRef)
            rebuildContactsFromSources(subData)
            if (subData.sourceRefs.isEmpty()) {
                dynamic.remove(uid)
            }
            return@withLock "UID $uid 的订阅联系人数量已达上限 $MAX_CONTACTS_PER_UID"
        }

        cleanupRemovedContactFilters(uid, oldContacts - subData.contacts)
        "分组 $groupName 订阅 ${subData.name} 成功!"
    }

    suspend fun removeGroupSubscribe(uid: Long, groupName: String) = mutex.withLock {
        val user = dynamic[uid] ?: return@withLock "还未订阅此人哦"
        val sourceRef = groupSourceRef(groupName)
        if (sourceRef !in user.sourceRefs) return@withLock "分组 $groupName 未订阅该UP主"

        val oldContacts = user.contacts.toSet()
        user.sourceRefs.remove(sourceRef)
        rebuildContactsFromSources(user)
        cleanupRemovedContactFilters(uid, oldContacts - user.contacts)

        if (user.sourceRefs.isEmpty()) {
            removeUidCompletely(uid, oldContacts)
        }

        return@withLock "分组 $groupName 取消订阅 ${user.name} 成功"
    }

    suspend fun refreshGroupRef(groupName: String) = mutex.withLock {
        val sourceRef = groupSourceRef(groupName)
        dynamic.forEach { (uid, subData) ->
            if (sourceRef !in subData.sourceRefs) return@forEach
            val oldContacts = subData.contacts.toSet()
            rebuildContactsFromSources(subData)
            cleanupRemovedContactFilters(uid, oldContacts - subData.contacts)
        }
    }

    suspend fun deleteGroupRef(groupName: String) = mutex.withLock {
        val sourceRef = groupSourceRef(groupName)
        dynamic.toMap().forEach { (uid, subData) ->
            if (sourceRef !in subData.sourceRefs) return@forEach

            val oldContacts = subData.contacts.toSet()
            subData.sourceRefs.remove(sourceRef)
            rebuildContactsFromSources(subData)
            cleanupRemovedContactFilters(uid, oldContacts - subData.contacts)

            if (subData.sourceRefs.isEmpty()) {
                removeUidCompletely(uid, oldContacts)
            }
        }
    }

    // 兼容旧调用路径: /add 和快速命令都视为 direct 来源
    suspend fun addSubscribe(uid: Long, subject: String, isSelf: Boolean = true): String {
        return addDirectSubscribe(uid, subject, isSelf)
    }

    // 兼容旧调用路径
    suspend fun removeSubscribe(uid: Long, subject: String, isSelf: Boolean = true): String {
        return removeDirectSubscribe(uid, subject, isSelf)
    }

    suspend fun removeAllSubscribe(subject: String) = mutex.withLock {
        val normalizedSubject = normalizeSubject(subject) ?: subject
        val directRef = directSourceRef(normalizedSubject)

        filter.remove(normalizedSubject)
        group.forEach { (_, g) -> g.contacts.remove(normalizedSubject) }

        dynamic.toMap().forEach { (uid, subData) ->
            if (directRef !in subData.sourceRefs && normalizedSubject !in subData.contacts) return@forEach

            val oldContacts = subData.contacts.toSet()
            subData.sourceRefs.remove(directRef)
            rebuildContactsFromSources(subData)
            cleanupRemovedContactFilters(uid, oldContacts - subData.contacts)

            if (subData.sourceRefs.isEmpty()) {
                removeUidCompletely(uid, oldContacts)
            }
        }
    }

    suspend fun list(subject: String) = mutex.withLock {
        buildString {
            appendLine("目标: $subject")
            appendLine()
            appendLine("UP主: ")
            val c = dynamic.count { (uid, sub) ->
                if (subject in sub.contacts) {
                    appendLine("${sub.name}@$uid")
                    true
                } else false
            }
            if (c == 0) appendLine("无")
            appendLine()
            appendLine("番剧: ")
            val cc = bangumi.count { (ssid, sub) ->
                if (subject in sub.contacts) {
                    appendLine("${sub.title}@ss$ssid")
                    true
                } else false
            }
            if (cc == 0) appendLine("无")
            appendLine()
            append("共 ${c + cc} 个订阅")
        }
    }

    suspend fun listAll() = mutex.withLock {
        var count = 0
        buildString {
            appendLine("名称@UID#订阅人数")
            appendLine()
            dynamic.forEach { (uid, sub) ->
                appendLine("${sub.name}@$uid#${sub.contacts.size}")
                count++
            }
            appendLine()
            append("共 $count 个订阅")
        }
    }

    suspend fun listUser(uid: Long? = null) = mutex.withLock {
        buildString {
            val user = mutableSetOf<String>()
            if (uid == null) {
                dynamic.forEach { (_, sub) ->
                    user.addAll(sub.contacts)
                }
            } else {
                val u = dynamic[uid] ?: return@withLock "没有这个用户哦 [$uid]"
                appendLine("${u.name}[$uid]")
                appendLine()
                user.addAll(u.contacts)
            }

            val groups = mutableListOf<String>()
            val privates = mutableListOf<String>()
            val custom = mutableListOf<String>()

            user.forEach { subject ->
                val contact = parseContactId(subject)
                when (contact?.type) {
                    "group" -> groups.add("群@${contact.id}")
                    "private" -> privates.add("私聊@${contact.id}")
                    else -> custom.add(subject)
                }
            }

            appendLine("====群====")
            if (groups.isEmpty()) appendLine("无") else groups.forEach { appendLine(it) }
            appendLine("====私聊====")
            if (privates.isEmpty()) appendLine("无") else privates.forEach { appendLine(it) }
            appendLine("====分组/其他====")
            if (custom.isEmpty()) appendLine("无") else custom.forEach { appendLine(it) }
            appendLine()
            append("共 ${user.size} 个联系人")
        }
    }
}
