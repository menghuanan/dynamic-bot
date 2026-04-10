package top.bilibili.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import top.bilibili.BiliData
import top.bilibili.TemplatePolicy

/**
 * 统一收口模板策略与运行态缓存的协同变更。
 * 当前先提供最小清理能力，后续任务会继续把更多读写入口迁入此协调层。
 */
object TemplateRuntimeCoordinator {
    private val mutex = Mutex()

    /**
     * 为后续需要串行化的模板运行态操作提供共享锁。
     * 当前批次先暴露统一入口，避免不同服务各自创建不相关的并发边界。
     */
    suspend fun <T> withLock(block: () -> T): T = mutex.withLock { block() }

    /**
     * 读取指定作用域下的模板策略快照。
     * 读取方拿到的是深拷贝，避免命令层直接持有运行中的可变策略引用。
     */
    fun readScopePolicies(type: String, scope: String): MutableMap<Long, TemplatePolicy> {
        return runLocked {
            copyPolicyEntries(policyMap(type)?.get(scope) ?: mutableMapOf())
        }
    }

    /**
     * 追加模板到指定作用域和 UID 的策略列表末尾。
     * 若模板已存在则保持原顺序不变，并返回 false 交由上层复用原有提示文案。
     */
    fun appendTemplate(type: String, scope: String, uid: Long, template: String): Boolean {
        return runLocked {
            val policy = policyMap(type)
                ?.getOrPut(scope) { mutableMapOf() }
                ?.getOrPut(uid) { TemplatePolicy() }
                ?: return@runLocked false
            if (policy.templates.contains(template)) {
                return@runLocked false
            }
            policy.templates.add(template)
            true
        }
    }

    /**
     * 删除指定 UID 的模板，并在策略为空时回收对应运行态缓存。
     * 若该 UID 仍保留其他模板，则只清理该 UID 的运行态而不影响同作用域其他 UID。
     */
    fun removeTemplate(type: String, scope: String, uid: Long, template: String): RemoveTemplateResult {
        return runLocked {
            val policiesByUid = policyMap(type)?.get(scope) ?: return@runLocked RemoveTemplateResult.POLICY_MISSING
            val policy = policiesByUid[uid] ?: return@runLocked RemoveTemplateResult.POLICY_MISSING
            if (!policy.templates.remove(template)) {
                return@runLocked RemoveTemplateResult.TEMPLATE_MISSING
            }
            if (policy.templates.isEmpty()) {
                policiesByUid.remove(uid)
                TemplateSelectionService.clearRuntimeBinding(type, scope, uid)
                if (policiesByUid.isEmpty()) {
                    policyMap(type)?.remove(scope)
                }
                return@runLocked RemoveTemplateResult.REMOVED_UID
            }
            RemoveTemplateResult.UPDATED
        }
    }

    /**
     * 设置指定 UID 策略的随机开关。
     * 返回 false 表示目标策略不存在，上层可继续输出既有错误提示。
     */
    fun setRandomEnabled(type: String, scope: String, uid: Long, enabled: Boolean): Boolean {
        return runLocked {
            val policy = policyMap(type)?.get(scope)?.get(uid) ?: return@runLocked false
            policy.randomEnabled = enabled
            true
        }
    }

    /**
     * 删除指定类型与作用域下的整段模板策略，并同步清理该作用域的运行态缓存。
     * 该入口用于作用域被整体移除时，避免 last-used 与批次缓存残留悬空引用。
     */
    fun removeScope(type: String, scope: String) {
        policyMap(type)?.remove(scope)
        TemplateSelectionService.clearRuntimeScope(type, scope)
    }

    /**
     * 删除指定 UID 在全部模板类型中的策略绑定，并清理跨类型运行态缓存。
     * 生命周期删除逻辑通过该入口可以保持策略表与运行态缓存同步回收。
     */
    fun removeUidAcrossTypes(uid: Long) {
        listOf("dynamic", "live", "liveClose").forEach { type ->
            policyMap(type)?.entries?.removeIf { (_, policiesByUid) ->
                policiesByUid.remove(uid)
                policiesByUid.isEmpty()
            }
        }
        TemplateSelectionService.clearRuntimeUid(uid)
    }

    /**
     * 使用整份新策略替换当前三类模板策略表，并重置全部选择运行态缓存。
     * 配置重载和快照回放都需要同一入口保证运行态不会继续引用旧策略。
     */
    fun replaceAllPolicies(
        dynamicPolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
        livePolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
        liveClosePolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
    ) {
        BiliData.dynamicTemplatePolicyByScope = copyPolicyMap(dynamicPolicies)
        BiliData.liveTemplatePolicyByScope = copyPolicyMap(livePolicies)
        BiliData.liveCloseTemplatePolicyByScope = copyPolicyMap(liveClosePolicies)
        TemplateSelectionService.clearAllRuntimeState()
    }

    /**
     * 导出当前三类模板策略的深拷贝快照。
     * 保存与序列化链路必须基于快照工作，避免读取过程中继续持有可变运行态引用。
     */
    fun snapshotPolicies(): TemplatePolicySnapshotBundle {
        return TemplatePolicySnapshotBundle(
            dynamic = copyPolicyMap(BiliData.dynamicTemplatePolicyByScope),
            live = copyPolicyMap(BiliData.liveTemplatePolicyByScope),
            liveClose = copyPolicyMap(BiliData.liveCloseTemplatePolicyByScope),
        )
    }

    /**
     * 解析模板类型对应的策略表。
     * 动态、开播、下播三类策略必须独立维护，避免跨类型误删或串写。
     */
    private fun policyMap(type: String): MutableMap<String, MutableMap<Long, TemplatePolicy>>? {
        return when (type) {
            "dynamic" -> BiliData.dynamicTemplatePolicyByScope
            "live" -> BiliData.liveTemplatePolicyByScope
            "liveClose" -> BiliData.liveCloseTemplatePolicyByScope
            else -> null
        }
    }

    /**
     * 以同步方式复用协调器的共享锁。
     * 现有命令服务大多是同步 API，这里统一桥接到同一把 Mutex。
     */
    private fun <T> runLocked(block: () -> T): T {
        return runBlocking { withLock(block) }
    }

    /**
     * 深拷贝单个作用域下的 UID -> policy 策略表。
     * 调用方只读使用时不应反向修改运行中的真实策略。
     */
    private fun copyPolicyEntries(
        source: MutableMap<Long, TemplatePolicy>,
    ): MutableMap<Long, TemplatePolicy> {
        return source.mapValuesTo(mutableMapOf()) { (_, policy) ->
            TemplatePolicy(
                templates = policy.templates.toMutableList(),
                randomEnabled = policy.randomEnabled,
            )
        }
    }

    /**
     * 深拷贝 scope -> uid -> policy 的嵌套策略表。
     * 模板策略里的模板列表同样需要复制，避免新旧快照共享同一可变列表实例。
     */
    private fun copyPolicyMap(
        source: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
    ): MutableMap<String, MutableMap<Long, TemplatePolicy>> {
        return source.mapValuesTo(mutableMapOf()) { (_, policiesByUid) ->
            copyPolicyEntries(policiesByUid)
        }
    }
}

/**
 * 聚合导出的模板策略快照。
 * 三类消息策略需要分开携带，供保存链路按现有字段结构序列化。
 */
data class TemplatePolicySnapshotBundle(
    val dynamic: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
    val live: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
    val liveClose: MutableMap<String, MutableMap<Long, TemplatePolicy>>,
)

/**
 * 描述模板删除后的策略回收结果。
 * 协调层通过该结果告知调用方是模板缺失、策略缺失，还是已完成更新/回收。
 */
enum class RemoveTemplateResult {
    POLICY_MISSING,
    TEMPLATE_MISSING,
    UPDATED,
    REMOVED_UID,
}
