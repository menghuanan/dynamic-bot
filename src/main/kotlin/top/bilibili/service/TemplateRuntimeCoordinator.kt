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
    private val lastTemplateByScopeKey = mutableMapOf<String, String>()
    private val batchTemplateByMessageKey = mutableMapOf<String, String>()

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
     * 在共享锁内完成模板策略解析、随机选择与运行态更新。
     * 发送链路通过这一入口选择模板名，避免策略读取和 last-used 写入落在不同并发边界。
     */
    fun selectTemplateName(
        type: String,
        uid: Long,
        directScope: String?,
        groupScopes: List<String>,
        messageIdentity: String,
        templateExists: (String) -> Boolean,
    ): CoordinatorTemplateSelection? {
        return runLocked {
            val resolvedPolicy = resolvePolicy(type, uid, directScope, groupScopes) ?: return@runLocked null
            val validTemplates = resolvedPolicy.policy.templates.filter(templateExists)
            if (validTemplates.isEmpty()) {
                return@runLocked CoordinatorTemplateSelection(
                    scope = resolvedPolicy.scope,
                    templateName = null,
                )
            }

            if (!resolvedPolicy.policy.randomEnabled || validTemplates.size == 1) {
                val fixedTemplate = validTemplates.first()
                rememberSelection(type, resolvedPolicy.scope, uid, messageIdentity, fixedTemplate)
                return@runLocked CoordinatorTemplateSelection(
                    scope = resolvedPolicy.scope,
                    templateName = fixedTemplate,
                )
            }

            val batchKey = buildBatchKey(type, resolvedPolicy.scope, uid, messageIdentity)
            if (resolvedPolicy.scope.startsWith("groupRef:")) {
                batchTemplateByMessageKey[batchKey]?.let { cachedTemplate ->
                    return@runLocked CoordinatorTemplateSelection(
                        scope = resolvedPolicy.scope,
                        templateName = cachedTemplate,
                    )
                }
            }

            val lastTemplate = lastTemplateByScopeKey[buildLastUsedKey(type, resolvedPolicy.scope, uid)]
            val candidates = validTemplates.filter { templateName -> templateName != lastTemplate }.ifEmpty { validTemplates }
            val selectedTemplate = candidates.random(kotlin.random.Random.Default)
            rememberSelection(type, resolvedPolicy.scope, uid, messageIdentity, selectedTemplate)
            CoordinatorTemplateSelection(
                scope = resolvedPolicy.scope,
                templateName = selectedTemplate,
            )
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
                clearRuntimeBinding(type, scope, uid)
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
        clearRuntimeScope(type, scope)
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
        clearRuntimeUid(uid)
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
        clearAllRuntimeState()
    }

    /**
     * 清理单条消息批次的 group scope 复用缓存。
     * 发送批次结束后通过该入口回收临时随机状态，避免后续消息误复用旧批次结果。
     */
    fun clearBatchSelections(messageIdentity: String) {
        val suffix = "|$messageIdentity"
        runLocked {
            batchTemplateByMessageKey.keys
                .filter { key -> key.endsWith(suffix) }
                .forEach { key -> batchTemplateByMessageKey.remove(key) }
        }
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
     * 导出 last-used 运行态快照，供测试断言缓存清理行为。
     * 返回值是副本，调用方不能借此修改真实运行态。
     */
    internal fun snapshotLastTemplateState(): Map<String, String> {
        return runLocked { lastTemplateByScopeKey.toMap() }
    }

    /**
     * 导出批次模板缓存快照，供测试断言批次清理行为。
     * 返回值只用于观测，不参与任何业务修改。
     */
    internal fun snapshotBatchTemplateState(): Map<String, String> {
        return runLocked { batchTemplateByMessageKey.toMap() }
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
     * 解析命中的模板策略。
     * direct contact 优先于 groupRef，多组命中时维持传入顺序决定的首个命中结果。
     */
    private fun resolvePolicy(
        type: String,
        uid: Long,
        directScope: String?,
        groupScopes: List<String>,
    ): CoordinatorResolvedPolicy? {
        val policies = policyMap(type) ?: return null
        if (directScope != null) {
            policies[directScope]?.get(uid)?.let { policy ->
                return CoordinatorResolvedPolicy(directScope, policy)
            }
        }

        val matchedGroupScope = groupScopes.firstOrNull { scope ->
            policies[scope]?.containsKey(uid) == true
        } ?: return null
        return CoordinatorResolvedPolicy(
            scope = matchedGroupScope,
            policy = policies[matchedGroupScope]!!.getValue(uid),
        )
    }

    /**
     * 记录一次模板选择结果。
     * groupRef 作用域会同时写入批次缓存，联系人直绑只更新 last-used 状态。
     */
    private fun rememberSelection(
        type: String,
        scope: String,
        uid: Long,
        messageIdentity: String,
        templateName: String,
    ) {
        lastTemplateByScopeKey[buildLastUsedKey(type, scope, uid)] = templateName
        if (scope.startsWith("groupRef:")) {
            batchTemplateByMessageKey[buildBatchKey(type, scope, uid, messageIdentity)] = templateName
        }
    }

    /**
     * 清理指定类型与作用域对应的运行态缓存。
     * 当整段策略作用域被删除时，需要同时回收 last-used 与批次复用状态。
     */
    private fun clearRuntimeScope(type: String, scope: String) {
        val scopePrefix = "$type|$scope|"
        lastTemplateByScopeKey.keys
            .filter { key -> key.startsWith(scopePrefix) }
            .forEach { key -> lastTemplateByScopeKey.remove(key) }
        batchTemplateByMessageKey.keys
            .filter { key -> key.startsWith(scopePrefix) }
            .forEach { key -> batchTemplateByMessageKey.remove(key) }
    }

    /**
     * 清理指定 UID 在全部类型与作用域中的运行态缓存。
     * 生命周期删除会跨模板类型回收 UID 绑定，这里同步移除所有残留选择状态。
     */
    private fun clearRuntimeUid(uid: Long) {
        val uidSuffix = "|$uid"
        lastTemplateByScopeKey.keys
            .filter { key -> key.endsWith(uidSuffix) }
            .forEach { key -> lastTemplateByScopeKey.remove(key) }
        batchTemplateByMessageKey.keys
            .filter { key -> key.contains("$uidSuffix|") }
            .forEach { key -> batchTemplateByMessageKey.remove(key) }
    }

    /**
     * 清理单个类型、作用域与 UID 对应的运行态缓存。
     * 当只删除某个 UID 的最后一个模板时，需要精确回收该绑定而不是清空整个作用域。
     */
    private fun clearRuntimeBinding(type: String, scope: String, uid: Long) {
        lastTemplateByScopeKey.remove(buildLastUsedKey(type, scope, uid))
        val batchPrefix = "$type|$scope|$uid|"
        batchTemplateByMessageKey.keys
            .filter { cacheKey -> cacheKey.startsWith(batchPrefix) }
            .forEach { cacheKey -> batchTemplateByMessageKey.remove(cacheKey) }
    }

    /**
     * 清空全部模板选择运行态缓存。
     * 全量重载策略表时必须同步重置运行态，避免旧缓存继续影响新配置。
     */
    private fun clearAllRuntimeState() {
        lastTemplateByScopeKey.clear()
        batchTemplateByMessageKey.clear()
    }

    /**
     * 构造 last-used 缓存 key。
     * key 中显式包含 type、scope 与 uid，保证不同消息类型与作用域之间互不串扰。
     */
    private fun buildLastUsedKey(type: String, scope: String, uid: Long): String {
        return "$type|$scope|$uid"
    }

    /**
     * 构造分组批次缓存 key。
     * 只有 group scope 会复用同一 messageIdentity 下的随机结果，联系人直绑不会进入该缓存。
     */
    private fun buildBatchKey(type: String, scope: String, uid: Long, messageIdentity: String): String {
        return "$type|$scope|$uid|$messageIdentity"
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
 * 协调层返回的模板名选择结果。
 * 若模板策略存在但全部模板失效，则 templateName 为空，由上层执行默认模板回退。
 */
data class CoordinatorTemplateSelection(
    val scope: String,
    val templateName: String?,
)

/**
 * 协调层内部使用的命中策略快照。
 * 解析完成后保留 scope 和策略内容，供同一把锁内继续完成随机选择。
 */
private data class CoordinatorResolvedPolicy(
    val scope: String,
    val policy: TemplatePolicy,
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
