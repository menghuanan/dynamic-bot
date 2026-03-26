package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliConfigManager
import top.bilibili.Group
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parseContactId

object GroupService {
    private val mutex = Mutex()

    private fun normalizeSubject(subject: String): String? {
        return normalizeContactSubject(subject)
    }

    suspend fun createGroup(name: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) {
            if (name.matches("^[0-9]*$".toRegex())) return@withLock "分组名不能全为数字"
            group[name] = Group(name, operator)
            "创建成功"
        } else {
            "分组名称重复"
        }
    }

    suspend fun delGroup(name: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) return@withLock "没有此分组 [$name]"
        if (group[name]!!.creator != operator) return@withLock "无权删除"

        dynamic.forEach { (_, s) -> s.contacts.remove(name) }
        BiliConfigManager.data.dynamicPushTemplate.forEach { (_, c) -> c.remove(name) }
        BiliConfigManager.data.livePushTemplate.forEach { (_, c) -> c.remove(name) }
        BiliConfigManager.data.liveCloseTemplate.forEach { (_, c) -> c.remove(name) }
        filter.remove(name)
        atAll.remove(name)
        group.remove(name)
        "删除成功"
    }

    suspend fun listGroup(name: String? = null, operator: Long) = mutex.withLock {
        if (name == null) {
            group.values.filter {
                operator == BiliConfigManager.config.admin || operator == it.creator || it.admin.contains(operator)
            }.joinToString("\n") { "${it.name}@${it.creator}" }
                .ifEmpty { "没有创建或管理任何分组哦" }
        } else {
            group[name]?.toString() ?: "没有此分组哦"
        }
    }

    suspend fun setGroupAdmin(name: String, contacts: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) return@withLock "没有此分组 [$name]"
        if (group[name]!!.creator != operator) return@withLock "无权添加"

        var failMsg = ""
        group[name]?.admin?.addAll(contacts.split(",", "，").mapNotNull { raw ->
            val normalized = normalizeSubject(raw)
            if (normalized == null) {
                failMsg += "$raw, "
                null
            } else {
                val contact = parseContactId(normalized)
                if (contact?.type == "private") {
                    contact.id
                } else {
                    failMsg += "$raw(仅支持私聊管理员), "
                    null
                }
            }
        }.toSet())
        if (failMsg.isEmpty()) "添加成功" else "[$failMsg] 添加失败"
    }

    suspend fun banGroupAdmin(name: String, contacts: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) return@withLock "没有此分组 [$name]"
        if (group[name]!!.creator != operator) return@withLock "无权删除"

        var failMsg = ""
        val admins = group[name]!!.admin
        contacts.split(",", "，").mapNotNull { raw ->
            val normalized = normalizeSubject(raw)
            if (normalized == null) {
                failMsg += "$raw, "
                null
            } else {
                parseContactId(normalized)?.id
            }
        }.toSet().forEach {
            if (!admins.remove(it)) failMsg += "$it, "
        }

        if (failMsg.isEmpty()) "删除成功" else "[$failMsg] 删除失败"
    }

    suspend fun pushGroupContact(name: String, contacts: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) return@withLock "没有此分组 [$name]"
        if (!checkGroupPerm(name, operator)) return@withLock "无权添加"

        var failMsg = ""
        group[name]?.contacts?.addAll(contacts.split(",", "，").mapNotNull { raw ->
            val normalized = normalizeSubject(raw)
            if (normalized == null) {
                failMsg += "$raw, "
                null
            } else {
                normalized
            }
        }.toSet())

        if (failMsg.isEmpty()) "添加成功" else "[$failMsg] 添加失败"
    }

    suspend fun delGroupContact(name: String, contacts: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) return@withLock "没有此分组 [$name]"
        if (!checkGroupPerm(name, operator)) return@withLock "无权删除"

        var failMsg = ""
        group[name]?.contacts?.removeAll(contacts.split(",", "，").mapNotNull { raw ->
            val normalized = normalizeSubject(raw)
            if (normalized == null) {
                failMsg += "$raw, "
                null
            } else {
                normalized
            }
        }.toSet())

        if (failMsg.isEmpty()) "删除成功" else "[$failMsg] 删除失败"
    }

    fun checkGroupPerm(name: String, operator: Long): Boolean =
        group[name]?.creator == operator || group[name]?.admin?.contains(operator) == true
}
