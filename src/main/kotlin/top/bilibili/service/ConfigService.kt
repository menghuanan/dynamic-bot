package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.parsePlatformContact
import top.bilibili.utils.subjectsEquivalent

object ConfigService {
    fun configOverview(uid: Long = 0L, subject: String): String {
        parsePlatformContact(subject) ?: return "联系人格式错误: $subject"
        if (uid < 0L) return "UID 格式错误: $uid"

        val normalizedSubject = normalizeContactSubject(subject) ?: return "联系人格式错误: $subject"
        if (uid > 0L && !isFollow(uid, normalizedSubject)) return "该群未订阅 UID: $uid"

        val dynamicCustom = BiliData.dynamicPushTemplate.entries.find { (_, users) ->
            users.any { subjectsEquivalent(it, normalizedSubject) }
        }?.key
        val liveCustom = BiliData.livePushTemplate.entries.find { (_, users) ->
            users.any { subjectsEquivalent(it, normalizedSubject) }
        }?.key
        val liveCloseCustom = BiliData.liveCloseTemplate.entries.find { (_, users) ->
            users.any { subjectsEquivalent(it, normalizedSubject) }
        }?.key

        val dynamicUidCustom = if (uid > 0L) BiliData.dynamicPushTemplateByUid[normalizedSubject]?.get(uid) else null
        val liveUidCustom = if (uid > 0L) BiliData.livePushTemplateByUid[normalizedSubject]?.get(uid) else null
        val liveCloseUidCustom = if (uid > 0L) BiliData.liveCloseTemplateByUid[normalizedSubject]?.get(uid) else null

        val runtimeConfig = runCatching { BiliConfigManager.config }.getOrElse { BiliConfig() }
        val dynamicBinding = dynamicUidCustom ?: dynamicCustom ?: runtimeConfig.templateConfig.defaultDynamicPush
        val liveBinding = liveUidCustom ?: liveCustom ?: runtimeConfig.templateConfig.defaultLivePush
        val liveCloseBinding = liveCloseUidCustom ?: liveCloseCustom ?: runtimeConfig.templateConfig.defaultLiveClose

        val dynamicSource = when {
            dynamicUidCustom != null -> "UID自定义"
            dynamicCustom != null -> "群自定义"
            else -> "默认"
        }
        val liveSource = when {
            liveUidCustom != null -> "UID自定义"
            liveCustom != null -> "群自定义"
            else -> "默认"
        }
        val liveCloseSource = when {
            liveCloseUidCustom != null -> "UID自定义"
            liveCloseCustom != null -> "群自定义"
            else -> "默认"
        }

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
            appendLine("- 动态: $dynamicBinding ($dynamicSource)")
            appendLine("- 直播: $liveBinding ($liveSource)")
            appendLine("- 下播: $liveCloseBinding ($liveCloseSource)")
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

    @Suppress("UNUSED_PARAMETER")
    suspend fun config(event: Any, uid: Long = 0L, contact: Any): String {
        val subject = contact as? String ?: return "联系人参数错误"
        return configOverview(uid, subject)
    }
}
