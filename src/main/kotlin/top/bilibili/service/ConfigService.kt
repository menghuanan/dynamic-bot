package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.TemplatePolicy
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parsePlatformContact
import top.bilibili.utils.subjectsEquivalent

/**
 * 统一生成会话级配置概览，避免命令层直接拼装多处运行时状态。
 */
object ConfigService {
    /**
     * 汇总模板、颜色、过滤器与订阅信息，保证展示口径始终一致。
     */
    fun configOverview(uid: Long = 0L, subject: String): String {
        val normalizedSubject = normalizeContactSubject(subject) ?: return "联系人格式错误: $subject"
        parsePlatformContact(normalizedSubject) ?: return "联系人格式错误: $subject"
        if (uid < 0L) return "UID 格式错误: $uid"
        if (uid > 0L && !isFollow(uid, normalizedSubject)) return "该群未订阅 UID: $uid"

        val runtimeConfig = runCatching { BiliConfigManager.config }.getOrElse { BiliConfig() }
        val dynamicBinding = describeTemplatePolicy("d", uid, normalizedSubject, runtimeConfig)
        val liveBinding = describeTemplatePolicy("l", uid, normalizedSubject, runtimeConfig)
        val liveCloseBinding = describeTemplatePolicy("le", uid, normalizedSubject, runtimeConfig)

        val atAllSummary = if (uid > 0L) {
            BiliData.atAll[normalizedSubject]?.get(uid)?.joinToString(",") { it.value } ?: "无"
        } else {
            val all = BiliData.atAll[normalizedSubject]
            if (all.isNullOrEmpty()) {
                "无"
            } else {
                all.toSortedMap().entries.joinToString("\n") { (scopeUid, types) ->
                    "UID($scopeUid): ${types.joinToString(",") { it.value }}"
                }
            }
        }

        val filterMap = BiliData.filter[normalizedSubject]
        val uidFilter = filterMap?.get(uid)
        val globalFilter = filterMap?.get(0L)
        val filterSummary = buildString {
            if (uidFilter == null && globalFilter == null) {
                append("无")
            } else {
                if (uidFilter != null) {
                    appendLine("UID($uid): 类型=${uidFilter.typeSelect.mode.value}(${uidFilter.typeSelect.list.size}) 正则=${uidFilter.regularSelect.mode.value}(${uidFilter.regularSelect.list.size})")
                }
                if (globalFilter != null) {
                    append("全局(0): 类型=${globalFilter.typeSelect.mode.value}(${globalFilter.typeSelect.list.size}) 正则=${globalFilter.regularSelect.mode.value}(${globalFilter.regularSelect.list.size})")
                }
            }
        }.trim()

        val dynamicCount = if (uid > 0L) {
            if (isFollow(uid, normalizedSubject)) 1 else 0
        } else {
            BiliData.dynamic.values.count { sub -> sub.contacts.any { subjectsEquivalent(it, normalizedSubject) } }
        }
        val bangumiCount = BiliData.bangumi.values.count { sub -> sub.contacts.any { subjectsEquivalent(it, normalizedSubject) } }

        return buildString {
            appendLine("配置概览")
            appendLine("目标: $subject")
            appendLine("用户UID: ${if (uid == 0L) "全局" else uid}")
            appendLine()
            appendLine("颜色:")
            if (uid == 0L) {
                appendLine("- 默认主题色: ${runtimeConfig.imageConfig.defaultColor}")
                appendLine("- 设置命令: /bili config color <uid|用户名> <HEX颜色>")
            } else {
                appendLine("- 当前主题色: ${DynamicService.resolveColor(uid, normalizedSubject)}")
                appendLine("- 设置命令: /bili config color $uid <HEX颜色>")
            }
            appendLine()
            appendLine("模板:")
            appendLine("- 动态: $dynamicBinding")
            appendLine("- 直播: $liveBinding")
            appendLine("- 下播: $liveCloseBinding")
            appendLine()
            appendLine("At全体:")
            appendLine(atAllSummary)
            appendLine()
            appendLine("过滤器摘要:")
            appendLine(filterSummary)
            appendLine()
            appendLine("订阅摘要:")
            appendLine("- 动态订阅命中: $dynamicCount")
            if (uid == 0L) {
                appendLine("- 番剧订阅命中: $bangumiCount")
            }
        }.trim()
    }

    /**
     * 为旧调用方保留兼容入口，只接受已可转为联系人字符串的参数。
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun config(event: Any, uid: Long = 0L, contact: Any): String {
        val subject = contact as? String ?: return "联系人参数错误"
        return configOverview(uid, subject)
    }

    /**
     * 生成单类消息的模板策略摘要。
     * 概览优先展示 direct contact 策略，其次展示命中的 groupRef 策略，最后回退全局默认模板。
     */
    private fun describeTemplatePolicy(type: String, uid: Long, normalizedSubject: String, runtimeConfig: BiliConfig): String {
        val defaultName = defaultTemplateName(type, runtimeConfig)
        if (uid == 0L) {
            val directPolicies = policyMap(type)["contact:$normalizedSubject"].orEmpty()
            if (directPolicies.isEmpty()) {
                return "默认模板: $defaultName"
            }
            return directPolicies.toSortedMap().entries.joinToString(" | ") { (policyUid, policy) ->
                "UID($policyUid) ${formatPolicy(policy, type)}"
            }
        }

        val directPolicy = policyMap(type)["contact:$normalizedSubject"]?.get(uid)
        if (directPolicy != null) {
            return "联系人策略 ${formatPolicy(directPolicy, type)}"
        }

        val matchedGroupScope = matchedGroupScopes(uid, normalizedSubject).firstOrNull { scope ->
            policyMap(type)[scope]?.containsKey(uid) == true
        }
        if (matchedGroupScope != null) {
            val groupName = matchedGroupScope.removePrefix("groupRef:")
            val groupPolicy = policyMap(type)[matchedGroupScope]?.get(uid)
            if (groupPolicy != null) {
                return "分组策略($groupName) ${formatPolicy(groupPolicy, type)}"
            }
        }

        return "默认模板: $defaultName"
    }

    /**
     * 根据 UID 订阅来源解析当前联系人命中的 groupRef scope。
     * 只返回实际包含当前联系人的分组，避免配置概览误报无关分组策略。
     */
    private fun matchedGroupScopes(uid: Long, normalizedSubject: String): List<String> {
        return BiliData.dynamic[uid]?.sourceRefs
            ?.mapNotNull { sourceRef ->
                if (!sourceRef.startsWith("groupRef:")) {
                    null
                } else {
                    val groupName = sourceRef.removePrefix("groupRef:")
                    val matched = BiliData.group[groupName]?.contacts?.any { contact ->
                        subjectsEquivalent(contact, normalizedSubject)
                    } == true
                    if (matched) "groupRef:$groupName" else null
                }
            }
            ?.distinct()
            ?.sorted()
            .orEmpty()
    }

    /**
     * 将模板策略压缩为单行摘要。
     * 摘要包含顺位、随机状态和失效模板标记，方便在 config 概览里直接排查策略问题。
     */
    private fun formatPolicy(policy: TemplatePolicy, type: String): String {
        val orderedTemplates = policy.templates.withIndex().joinToString(", ") { (index, name) ->
            val invalidSuffix = if (templateExists(type, name)) "" else " [失效]"
            "${index + 1}.$name$invalidSuffix"
        }
        val firstTemplate = policy.templates.firstOrNull() ?: "默认模板"
        return "顺位1: $firstTemplate, 随机: ${if (policy.randomEnabled) "开启" else "关闭"}, 模板: $orderedTemplates"
    }

    /**
     * 判断模板名在当前运行时配置中是否仍然有效。
     * 配置概览只做展示，不在这里静默改写持久化策略。
     */
    private fun templateExists(type: String, templateName: String): Boolean {
        return when (type) {
            "d" -> BiliConfigManager.config.templateConfig.dynamicPush.containsKey(templateName)
            "l" -> BiliConfigManager.config.templateConfig.livePush.containsKey(templateName)
            "le" -> BiliConfigManager.config.templateConfig.liveClose.containsKey(templateName)
            else -> false
        }
    }

    /**
     * 读取各类消息的全局默认模板名。
     * 当联系人与分组都没有专属策略时，配置概览会明确展示当前实际回退的默认模板。
     */
    private fun defaultTemplateName(type: String, runtimeConfig: BiliConfig): String {
        return when (type) {
            "d" -> runtimeConfig.templateConfig.defaultDynamicPush
            "l" -> runtimeConfig.templateConfig.defaultLivePush
            "le" -> runtimeConfig.templateConfig.defaultLiveClose
            else -> "OneMsg"
        }
    }

    /**
     * 获取指定类型的模板策略表。
     * Config 概览和命令层都依赖同一份 scope->uid->policy 结构，避免展示口径再次分叉。
     */
    private fun policyMap(type: String): MutableMap<String, MutableMap<Long, TemplatePolicy>> {
        return when (type) {
            "d" -> BiliData.dynamicTemplatePolicyByScope
            "l" -> BiliData.liveTemplatePolicyByScope
            "le" -> BiliData.liveCloseTemplatePolicyByScope
            else -> mutableMapOf()
        }
    }
}
