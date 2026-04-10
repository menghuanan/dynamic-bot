package top.bilibili.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliConfigManager
import top.bilibili.Group
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parseContactId

/**
 * 维护旧分组模型的增删改查接口，供历史命令和测试继续复用。
 */
object GroupService {
    private val mutex = Mutex()

    private fun normalizeSubject(subject: String): String? {
        return normalizeContactSubject(subject)
    }

    /**
     * 创建旧分组实体，并在入口处阻止纯数字名称混淆联系人标识。
     */
    suspend fun createGroup(name: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) {
            if (name.matches("^[0-9]*$".toRegex())) return@withLock "分组名不能全为数字"
            group[name] = Group(name, operator)
            "创建成功"
        } else {
            "分组名称重复"
        }
    }

    /**
     * 删除旧分组时同步清理关联订阅、模板、过滤器和 @全体 配置。
     */
    suspend fun delGroup(name: String, operator: Long) = mutex.withLock {
        if (!group.containsKey(name)) return@withLock "没有此分组 [$name]"
        if (group[name]!!.creator != operator) return@withLock "无权删除"

        dynamic.forEach { (_, s) -> s.contacts.remove(name) }
        // 分组删除后同步移除 groupRef scope 的模板策略，避免遗留悬空配置继续参与选择。
        BiliConfigManager.data.dynamicTemplatePolicyByScope.remove("groupRef:$name")
        BiliConfigManager.data.liveTemplatePolicyByScope.remove("groupRef:$name")
        BiliConfigManager.data.liveCloseTemplatePolicyByScope.remove("groupRef:$name")
        filter.remove(name)
        atAll.remove(name)
        group.remove(name)
        "删除成功"
    }

    /**
     * 查询旧分组列表或详情，并按操作人权限过滤可见范围。
     */
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

    /**
     * 为旧分组写入私聊管理员集合，避免非私聊联系人混入权限控制。
     */
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

    /**
     * 从旧分组移除管理员联系人，并把无法解析或不存在的目标汇总返回。
     */
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

    /**
     * 向旧分组追加联系人，并在入口统一完成 subject 规范化。
     */
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

    /**
     * 从旧分组移除联系人，并把无法归一化的输入一并反馈给调用方。
     */
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

    /**
     * 统一判断旧分组修改权限，避免各命令分支复制同一条件。
     */
    fun checkGroupPerm(name: String, operator: Long): Boolean =
        group[name]?.creator == operator || group[name]?.admin?.contains(operator) == true
}
