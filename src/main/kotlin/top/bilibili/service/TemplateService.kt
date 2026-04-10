package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.TemplatePolicy
import top.bilibili.connector.OutgoingPart
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parsePlatformContact

/**
 * 统一管理模板查询、预览与绑定，避免命令层直接接触模板存储结构。
 */
object TemplateService {
    private val runtimeConfig: BiliConfig
        get() = runCatching { BiliConfigManager.config }.getOrElse { BiliConfig() }

    private fun templateMap(type: String) = when (type) {
        "d" -> runtimeConfig.templateConfig.dynamicPush
        "l" -> runtimeConfig.templateConfig.livePush
        "le" -> runtimeConfig.templateConfig.liveClose
        else -> null
    }

    /**
     * 以文本形式列出指定类型模板，便于命令层直接回显模板内容。
     */
    fun listTemplateText(type: String): String {
        val templates = templateMap(type) ?: return "类型错误 d:动态 l:直播 le:直播结束"
        return buildString {
            appendLine("模板列表 ($type):")
            templates.forEach { (name, value) ->
                appendLine("- $name")
                appendLine("  ${value.replace("\r", "\\r").replace("\n", "\\n")}")
            }
        }.trim()
    }

    /**
     * 为旧调用方保留直接发送模板列表的入口，继续复用统一文本输出。
     */
    suspend fun listTemplate(type: String, subject: Any) {
        val subjectStr = subject as? String ?: return
        val contact = parsePlatformContact(subjectStr) ?: return
        MessageGatewayProvider.require().sendMessage(contact, listOf(OutgoingPart.text(listTemplateText(type))))
    }

    /**
     * 通过真实渲染链路预览模板，避免展示结果与实际推送效果不一致。
     */
    suspend fun previewTemplate(type: String, template: String, subject: String): String {
        val templates = templateMap(type) ?: return "类型错误 d:动态 l:直播 le:直播结束"
        val selected = templates[template] ?: return "没有这个模板: $template"
        val contact = parsePlatformContact(subject) ?: return "联系人格式错误"
        val normalizedSubject = normalizeContactSubject(subject) ?: return "联系人格式错误"
        val sampleMessage = buildSampleMessage(type, normalizedSubject) ?: return "类型错误 d:动态 l:直播 le:直播结束"
        val renderedSegments = TemplateRenderService.buildSegments(
            message = sampleMessage,
            contactStr = normalizedSubject,
            overrideTemplate = selected,
        )

        val previewSegments = buildList {
            add(OutgoingPart.text("模板预览: $template ($type)"))
            add(OutgoingPart.text("\n"))
            addAll(renderedSegments)
        }

        val sent = MessageGatewayProvider.require().sendMessage(contact, previewSegments)
        return if (sent) "预览已发送（基于实发渲染链路）" else "预览发送失败"
    }

    /**
     * 汇总模板占位符与作用范围说明，降低用户误配模板的概率。
     */
    fun explainTemplate(type: String): String {
        val scope = when (type) {
            "d" -> "动态推送模板"
            "l" -> "开播推送模板"
            "le" -> "下播推送模板"
            else -> return "类型错误 d:动态 l:直播 le:直播结束"
        }

        return buildString {
            appendLine("$scope 说明")
            appendLine("作用: 控制推送消息的文案布局与消息段顺序。")
            appendLine("可改动: 文本结构、换行拆分(\\r)、占位符展示内容。")
            appendLine()
            appendLine("常用占位符:")
            appendLine("{name} {uid} {mid} {time} {link}")
            appendLine("{type} {title} {content} {duration} {area}")
            appendLine("{draw} {images} {links}")
        }.trim()
    }

    /**
     * 兼容旧调用方的模板设置入口。
     * 新语义统一落到按 UID + scope 的策略模型上，因此这里只保留 UID 绑定能力。
     */
    fun setTemplate(type: String, template: String, subject: String, uid: Long? = null): String {
        if (uid == null) return "请使用 /bili template add <d|l|le> <模板名> <uid>"
        return addTemplate(type, template, subject, uid, null)
    }

    /**
     * 向指定 scope 的 UID 模板策略追加模板。
     * 新模板始终追加到末尾，保持固定模式与随机池共用同一份顺序列表。
     */
    fun addTemplate(type: String, template: String, subject: String, uid: Long, groupName: String?): String {
        val templates = templateMap(type) ?: return "类型错误 d:动态 l:直播 le:直播结束"
        val policyType = typeKey(type)
        if (!templates.containsKey(template)) return "没有这个模板: $template"

        val scope = resolveScope(type, subject, uid, groupName) ?: return scopeError(subject, uid, groupName)
        if (!TemplateRuntimeCoordinator.appendTemplate(policyType, scope.scopeKey, uid, template)) {
            return "模板已存在于当前策略中"
        }
        return "添加成功"
    }

    /**
     * 从指定 scope 的 UID 模板策略删除模板。
     * 删除后若有效模板不足 2 个，则自动关闭随机模式，避免留下不可用随机配置。
     */
    fun deleteTemplate(type: String, template: String, subject: String, uid: Long, groupName: String?): String {
        val scope = resolveScope(type, subject, uid, groupName) ?: return scopeError(subject, uid, groupName)
        val policyType = typeKey(type)
        val policy = TemplateRuntimeCoordinator.readScopePolicies(policyType, scope.scopeKey)[uid]
            ?: return "当前作用域未配置模板策略"
        if (!policy.templates.contains(template)) return "当前策略中不存在模板: $template"

        when (TemplateRuntimeCoordinator.removeTemplate(policyType, scope.scopeKey, uid, template)) {
            RemoveTemplateResult.POLICY_MISSING -> return "当前作用域未配置模板策略"
            RemoveTemplateResult.TEMPLATE_MISSING -> return "当前策略中不存在模板: $template"
            RemoveTemplateResult.REMOVED_UID -> return "删除成功"
            RemoveTemplateResult.UPDATED -> {
                val remainingPolicy = TemplateRuntimeCoordinator.readScopePolicies(policyType, scope.scopeKey)[uid]
                    ?: return "删除成功"
                if (validTemplateCount(type, remainingPolicy) <= 1) {
                    TemplateRuntimeCoordinator.setRandomEnabled(policyType, scope.scopeKey, uid, false)
                }
            }
        }
        return "删除成功"
    }

    /**
     * 列出指定 scope 下的模板策略摘要。
     * 摘要会标出模板顺序、随机状态与失效模板，帮助用户在命令行里核对当前策略。
     */
    fun listTemplatePolicy(type: String, subject: String, uid: Long?, groupName: String?): String {
        val scope = resolveScope(type, subject, uid, groupName, requireFollow = false)
            ?: return scopeError(subject, uid, groupName, requireUid = groupName != null)
        val scopePolicies = TemplateRuntimeCoordinator.readScopePolicies(typeKey(type), scope.scopeKey)
        if (scopePolicies.isEmpty()) {
            return "当前作用域未配置模板策略"
        }

        val entries = if (uid != null) {
            listOfNotNull(scopePolicies[uid]?.let { uid to it })
        } else {
            scopePolicies.toSortedMap().entries.map { it.toPair() }
        }
        if (entries.isEmpty()) {
            return "当前作用域未配置模板策略"
        }

        return buildString {
            appendLine("模板策略列表 (${scope.displayName})")
            entries.forEach { (policyUid, policy) ->
                appendLine("UID: $policyUid")
                appendLine("随机: ${if (policy.randomEnabled) "开启" else "关闭"}")
                appendLine("顺位1: ${policy.templates.firstOrNull() ?: "默认模板"}")
                appendLine(
                    "模板: " + policy.templates.withIndex().joinToString(", ") { (index, name) ->
                        val invalidSuffix = if (templateMap(type)?.containsKey(name) == true) "" else " [失效]"
                        "${index + 1}.$name$invalidSuffix"
                    },
                )
                appendLine()
            }
        }.trim()
    }

    /**
     * 开启指定 scope 的随机模板模式。
     * 开启前必须确认作用域已订阅、所有模板都有效，且有效模板数量至少为 2。
     */
    fun enableRandom(type: String, subject: String, uid: Long, groupName: String?): String {
        val scope = resolveScope(type, subject, uid, groupName) ?: return scopeError(subject, uid, groupName)
        val policyType = typeKey(type)
        val policy = TemplateRuntimeCoordinator.readScopePolicies(policyType, scope.scopeKey)[uid]
            ?: return "当前作用域未配置模板策略"
        if (policy.templates.any { templateName -> templateMap(type)?.containsKey(templateName) != true }) {
            return "当前策略包含已失效模板，请先清理后再开启随机"
        }
        if (validTemplateCount(type, policy) < 2) {
            return "开启随机至少需要 2 个有效模板"
        }
        TemplateRuntimeCoordinator.setRandomEnabled(policyType, scope.scopeKey, uid, true)
        return "开启成功"
    }

    /**
     * 关闭指定 scope 的随机模板模式。
     * 关闭随机不会删除模板列表，方便用户保留随机池后续再次开启。
     */
    fun disableRandom(type: String, subject: String, uid: Long, groupName: String?): String {
        val scope = resolveScope(type, subject, uid, groupName) ?: return scopeError(subject, uid, groupName)
        val policyType = typeKey(type)
        val policy = TemplateRuntimeCoordinator.readScopePolicies(policyType, scope.scopeKey)[uid]
            ?: return "当前作用域未配置模板策略"
        if (policy.templates.isEmpty()) return "当前作用域未配置模板策略"
        TemplateRuntimeCoordinator.setRandomEnabled(policyType, scope.scopeKey, uid, false)
        return "关闭成功"
    }

    /**
     * 解析 direct contact 与 groupRef 两类模板作用域。
     * group scope 只允许指向已存在且已订阅该 UID 的分组，避免写入悬空策略。
     */
    private fun resolveScope(
        type: String,
        subject: String,
        uid: Long?,
        groupName: String?,
        requireFollow: Boolean = true,
    ): ScopeResolution? {
        if (typeKey(type).isBlank()) return null
        if (uid == null) {
            if (groupName != null) {
                val group = BiliData.group[groupName] ?: return null
                return ScopeResolution(
                    scopeKey = "groupRef:$groupName",
                    displayName = "分组 ${group.name}",
                )
            }
            val normalizedSubject = normalizeContactSubject(subject) ?: return null
            return ScopeResolution(
                scopeKey = "contact:$normalizedSubject",
                displayName = normalizedSubject,
            )
        }
        val resolvedUid = uid
        if (resolvedUid <= 0L) return null

        if (groupName != null) {
            val group = BiliData.group[groupName] ?: return null
            val subscribed = BiliData.dynamic[resolvedUid]?.sourceRefs?.contains("groupRef:$groupName") == true
            if (requireFollow && !subscribed) return null
            return ScopeResolution(
                scopeKey = "groupRef:$groupName",
                displayName = "分组 ${group.name}",
            )
        }

        val normalizedSubject = normalizeContactSubject(subject) ?: return null
        if (requireFollow && !isFollow(resolvedUid, normalizedSubject)) return null
        return ScopeResolution(
            scopeKey = "contact:$normalizedSubject",
            displayName = normalizedSubject,
        )
    }

    /**
     * 统一生成 scope 解析失败提示。
     * 联系人和分组两类作用域的校验原因不同，这里集中返回更接近用户输入的错误文本。
     */
    private fun scopeError(subject: String, uid: Long?, groupName: String?, requireUid: Boolean = true): String {
        if (requireUid && (uid == null || uid <= 0L)) return "UID 格式错误"
        if (groupName != null) {
            if (!BiliData.group.containsKey(groupName)) return "没有这个分组: $groupName"
            return "该分组未订阅 UID: $uid"
        }
        return if (normalizeContactSubject(subject) == null) "联系人格式错误" else "该群未订阅 UID: $uid"
    }

    /**
     * 统计策略中当前仍有效的模板数量。
     * 删除逻辑和开启随机校验都依赖这个结果，避免失效模板把随机池数量“算多了”。
     */
    private fun validTemplateCount(type: String, policy: TemplatePolicy): Int {
        return policy.templates.count { templateName -> templateMap(type)?.containsKey(templateName) == true }
    }

    /**
     * 将命令层模板类型缩写转换为运行态协调层使用的完整类型键。
     * 统一转换后，策略服务与发送服务可以复用同一套运行态清理入口。
     */
    private fun typeKey(type: String): String {
        return when (type) {
            "d" -> "dynamic"
            "l" -> "live"
            "le" -> "liveClose"
            else -> ""
        }
    }

    private fun buildSampleMessage(type: String, subject: String): top.bilibili.data.BiliMessage? {
        return when (type) {
            "d" -> DynamicMessage(
                did = "1000000000",
                mid = 10086L,
                name = "示例UP",
                type = DynamicType.DYNAMIC_TYPE_AV,
                time = "2026-03-06 12:00:00",
                timestamp = 1772788800,
                content = "这是一条模板预览示例内容",
                images = emptyList(),
                links = listOf(DynamicMessage.Link(tag = "示例链接", value = "https://www.bilibili.com")),
                drawPath = null,
                contact = subject,
            )
            "l" -> LiveMessage(
                rid = 20000L,
                mid = 10086L,
                name = "示例主播",
                time = "2026-03-06 12:00:00",
                timestamp = 1772788800,
                title = "示例直播标题",
                cover = "https://example.com/cover.jpg",
                area = "示例分区",
                link = "https://live.bilibili.com/20000",
                drawPath = null,
                contact = subject,
            )
            "le" -> LiveCloseMessage(
                rid = 20000L,
                mid = 10086L,
                name = "示例主播",
                time = "2026-03-06 12:00:00",
                timestamp = 1772788800,
                endTime = "2026-03-06 13:20:00",
                duration = "1小时20分钟",
                title = "示例直播标题",
                area = "示例分区",
                link = "https://live.bilibili.com/20000",
                drawPath = null,
                contact = subject,
            )
            else -> null
        }
    }

    /**
     * 模板作用域解析结果。
     * 同时保留存储键与展示名称，避免命令回显再重复推导一次作用域文本。
     */
    private data class ScopeResolution(
        val scopeKey: String,
        val displayName: String,
    )
}
