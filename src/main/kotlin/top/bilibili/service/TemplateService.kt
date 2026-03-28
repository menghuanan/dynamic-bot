package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
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

    private fun pushBindingMap(type: String) = when (type) {
        "d" -> BiliData.dynamicPushTemplate
        "l" -> BiliData.livePushTemplate
        "le" -> BiliData.liveCloseTemplate
        else -> null
    }

    private fun pushBindingByUidMap(type: String) = when (type) {
        "d" -> BiliData.dynamicPushTemplateByUid
        "l" -> BiliData.livePushTemplateByUid
        "le" -> BiliData.liveCloseTemplateByUid
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
     * 绑定会话级或 UID 级模板，并在入口统一做类型和订阅校验。
     */
    fun setTemplate(type: String, template: String, subject: String, uid: Long? = null): String {
        val templates = templateMap(type) ?: return "类型错误 d:动态 l:直播 le:直播结束"
        val bindings = pushBindingMap(type) ?: return "类型错误 d:动态 l:直播 le:直播结束"
        val byUidBindings = pushBindingByUidMap(type) ?: return "类型错误 d:动态 l:直播 le:直播结束"
        val normalizedSubject = normalizeContactSubject(subject) ?: return "联系人格式错误"

        if (!templates.containsKey(template)) {
            return "没有这个模板: $template"
        }

        if (uid != null) {
            if (uid <= 0L) return "UID 格式错误"
            if (!isFollow(uid, normalizedSubject)) return "该群未订阅 UID: $uid"
            byUidBindings.getOrPut(normalizedSubject) { mutableMapOf() }[uid] = template
            return "配置完成"
        }

        bindings.forEach { (_, users) -> users.remove(normalizedSubject) }
        bindings.getOrPut(template) { mutableSetOf() }.add(normalizedSubject)
        return "配置完成"
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
}
