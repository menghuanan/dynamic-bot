package top.bilibili.service

import org.slf4j.LoggerFactory
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.TemplatePolicy
import kotlin.random.Random

/**
 * 负责在发送前解析最终模板。
 * 该服务只做策略选择，不承担占位符替换与消息段渲染职责。
 */
object TemplateSelectionService {
    private val logger = LoggerFactory.getLogger(TemplateSelectionService::class.java)

    // key: type + scope + uid，用于隔离不同作用域的上次模板记录。
    private val lastTemplateByScopeKey = mutableMapOf<String, String>()

    // key: type + group scope + uid + message identity，用于同分组单批次复用随机结果。
    private val batchTemplateByMessageKey = mutableMapOf<String, String>()

    private val runtimeConfig: BiliConfig
        get() = runCatching { BiliConfigManager.config }.getOrElse { BiliConfig() }

    /**
     * 选择当前消息最终应使用的模板。
     * 优先级固定为 direct contact > groupRef > global default。
     */
    fun selectTemplate(
        type: String,
        uid: Long,
        directScope: String?,
        groupScopes: List<String>,
        messageIdentity: String,
    ): SelectedTemplate {
        val resolvedScopes = groupScopes.distinct().sorted()
        resolvePolicy(type, uid, directScope, resolvedScopes)?.let { resolved ->
            return selectFromPolicy(
                type = type,
                uid = uid,
                resolvedPolicy = resolved,
                messageIdentity = messageIdentity,
            )
        }
        return fallbackToDefault(type)
    }

    /**
     * 清理单次消息发送留下的分组批次缓存。
     * 发送链路在一个批次结束后调用此入口，避免随机复用状态跨消息堆积。
     */
    fun clearBatchSelections(messageIdentity: String) {
        val suffix = "|$messageIdentity"
        batchTemplateByMessageKey.keys
            .filter { key -> key.endsWith(suffix) }
            .forEach { key -> batchTemplateByMessageKey.remove(key) }
    }

    /**
     * 清理指定类型与作用域对应的运行态缓存。
     * 当整段策略作用域被删除时，需要同时回收 last-used 与批次复用状态。
     */
    internal fun clearRuntimeScope(type: String, scope: String) {
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
    internal fun clearRuntimeUid(uid: Long) {
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
    internal fun clearRuntimeBinding(type: String, scope: String, uid: Long) {
        val key = buildLastUsedKey(type, scope, uid)
        lastTemplateByScopeKey.remove(key)
        val batchPrefix = "$type|$scope|$uid|"
        batchTemplateByMessageKey.keys
            .filter { cacheKey -> cacheKey.startsWith(batchPrefix) }
            .forEach { cacheKey -> batchTemplateByMessageKey.remove(cacheKey) }
    }

    /**
     * 清空全部模板选择运行态缓存。
     * 全量重载策略表时必须同步重置运行态，避免旧缓存继续影响新配置。
     */
    internal fun clearAllRuntimeState() {
        lastTemplateByScopeKey.clear()
        batchTemplateByMessageKey.clear()
    }

    /**
     * 按选择顺序解析命中的策略。
     * 多个 group scope 同时命中时固定按字典序取第一个，避免发送结果不稳定。
     */
    private fun resolvePolicy(
        type: String,
        uid: Long,
        directScope: String?,
        groupScopes: List<String>,
    ): ResolvedPolicy? {
        if (directScope != null) {
            policyMap(type)[directScope]?.get(uid)?.let { policy ->
                return ResolvedPolicy(directScope, policy)
            }
        }

        val matchedGroupScope = groupScopes.firstOrNull { scope ->
            policyMap(type)[scope]?.containsKey(uid) == true
        }
        if (matchedGroupScope != null) {
            if (groupScopes.count { scope -> policyMap(type)[scope]?.containsKey(uid) == true } > 1) {
                logger.warn("UID {} 同时命中多个分组模板策略，已按排序选择 {}", uid, matchedGroupScope)
            }
            return ResolvedPolicy(
                scope = matchedGroupScope,
                policy = policyMap(type)[matchedGroupScope]!!.getValue(uid),
            )
        }

        return null
    }

    /**
     * 从命中的策略中选出最终模板。
     * 固定模式优先顺位 1，随机模式在有效模板不少于 2 个时排除上次同 scope 模板。
     */
    private fun selectFromPolicy(
        type: String,
        uid: Long,
        resolvedPolicy: ResolvedPolicy,
        messageIdentity: String,
    ): SelectedTemplate {
        val validTemplates = resolvedPolicy.policy.templates.filter { templateName ->
            templateContent(type, templateName) != null
        }
        if (validTemplates.isEmpty()) {
            logger.warn("UID {} 的模板策略 {} 没有可用模板，回退全局默认模板", uid, resolvedPolicy.scope)
            return fallbackToDefault(type)
        }

        if (!resolvedPolicy.policy.randomEnabled || validTemplates.size == 1) {
            return buildSelectedTemplate(
                type = type,
                scope = resolvedPolicy.scope,
                uid = uid,
                templateName = validTemplates.first(),
            )
        }

        val batchKey = buildBatchKey(type, resolvedPolicy.scope, uid, messageIdentity)
        if (resolvedPolicy.scope.startsWith("groupRef:")) {
            batchTemplateByMessageKey[batchKey]?.let { cachedTemplate ->
                return buildSelectedTemplate(type, resolvedPolicy.scope, uid, cachedTemplate)
            }
        }

        val lastUsedKey = buildLastUsedKey(type, resolvedPolicy.scope, uid)
        val lastTemplate = lastTemplateByScopeKey[lastUsedKey]
        val candidates = validTemplates.filter { it != lastTemplate }.ifEmpty { validTemplates }
        val selectedName = candidates.random(Random.Default)
        if (resolvedPolicy.scope.startsWith("groupRef:")) {
            batchTemplateByMessageKey[batchKey] = selectedName
        }
        return buildSelectedTemplate(type, resolvedPolicy.scope, uid, selectedName)
    }

    /**
     * 构造最终模板结果并同步更新 last-used 状态。
     * last-used 只保存模板名，不落盘，避免为运行时随机状态引入持久化耦合。
     */
    private fun buildSelectedTemplate(
        type: String,
        scope: String,
        uid: Long,
        templateName: String,
    ): SelectedTemplate {
        val content = templateContent(type, templateName) ?: fallbackToDefault(type).templateContent
        lastTemplateByScopeKey[buildLastUsedKey(type, scope, uid)] = templateName
        return SelectedTemplate(
            templateName = templateName,
            templateContent = content,
            scope = scope,
        )
    }

    /**
     * 在没有命中策略或策略全部失效时回退到全局默认模板。
     * 默认模板正文缺失时继续回退到内置兜底正文，避免发送链路出现空模板。
     */
    private fun fallbackToDefault(type: String): SelectedTemplate {
        val config = runtimeConfig
        val defaultName = when (type) {
            "dynamic" -> config.templateConfig.defaultDynamicPush
            "live" -> config.templateConfig.defaultLivePush
            "liveClose" -> config.templateConfig.defaultLiveClose
            else -> "OneMsg"
        }
        return SelectedTemplate(
            templateName = defaultName,
            templateContent = templateContent(type, defaultName) ?: builtInFallbackTemplate(type),
            scope = null,
        )
    }

    /**
     * 读取指定类型模板池。
     * 模板选择服务只读取配置快照，避免直接依赖渲染层实现细节。
     */
    private fun templateContent(type: String, templateName: String): String? {
        val templateConfig = runtimeConfig.templateConfig
        return when (type) {
            "dynamic" -> templateConfig.dynamicPush[templateName]
            "live" -> templateConfig.livePush[templateName]
            "liveClose" -> templateConfig.liveClose[templateName]
            else -> null
        }
    }

    /**
     * 读取指定类型的策略表。
     * 三类消息各自维护独立策略，避免跨类型复用导致错误命中。
     */
    private fun policyMap(type: String): MutableMap<String, MutableMap<Long, TemplatePolicy>> {
        return when (type) {
            "dynamic" -> BiliData.dynamicTemplatePolicyByScope
            "live" -> BiliData.liveTemplatePolicyByScope
            "liveClose" -> BiliData.liveCloseTemplatePolicyByScope
            else -> mutableMapOf()
        }
    }

    /**
     * 生成 last-used 缓存 key。
     * key 中显式包含 type、scope 与 uid，保证不同消息类型与作用域之间互不串扰。
     */
    private fun buildLastUsedKey(type: String, scope: String, uid: Long): String {
        return "$type|$scope|$uid"
    }

    /**
     * 生成分组批次缓存 key。
     * 只有 group scope 会复用同一 messageIdentity 下的随机结果，联系人直绑不会进入该缓存。
     */
    private fun buildBatchKey(type: String, scope: String, uid: Long, messageIdentity: String): String {
        return "$type|$scope|$uid|$messageIdentity"
    }

    /**
     * 提供发送层异常场景下的内置模板兜底。
     * 即便配置中的默认模板名被删除，发送链路也应保持可用。
     */
    private fun builtInFallbackTemplate(type: String): String {
        return when (type) {
            "dynamic" -> "{draw}\n{name}@{type}\n{link}"
            "live" -> "{draw}\n{name}@直播\n{link}"
            "liveClose" -> "{name} 直播结束啦!\n直播时长: {duration}"
            else -> "{name}"
        }
    }
}

/**
 * 模板选择结果。
 * 同时返回模板名与模板正文，供发送链路直接复用而不必再次查表。
 */
data class SelectedTemplate(
    val templateName: String,
    val templateContent: String,
    val scope: String?,
)

/**
 * 命中的模板策略快照。
 * 该结构用于在选择过程中保留 scope 与策略内容的对应关系。
 */
private data class ResolvedPolicy(
    val scope: String,
    val policy: TemplatePolicy,
)
