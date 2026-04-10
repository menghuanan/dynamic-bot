package top.bilibili.service

import org.slf4j.LoggerFactory
import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager

/**
 * 负责在发送前解析最终模板。
 * 该服务只做策略选择，不承担占位符替换与消息段渲染职责。
 */
object TemplateSelectionService {
    private val logger = LoggerFactory.getLogger(TemplateSelectionService::class.java)

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
        TemplateRuntimeCoordinator.selectTemplateName(
            type = type,
            uid = uid,
            directScope = directScope,
            groupScopes = resolvedScopes,
            messageIdentity = messageIdentity,
            templateExists = { templateName -> templateContent(type, templateName) != null },
        )?.let { selection ->
            val selectedTemplateName = selection.templateName
            if (selectedTemplateName == null) {
                logger.warn("UID {} 的模板策略 {} 没有可用模板，回退全局默认模板", uid, selection.scope)
                return fallbackToDefault(type)
            }
            return buildSelectedTemplate(
                type = type,
                scope = selection.scope,
                templateName = selectedTemplateName,
            )
        }
        return fallbackToDefault(type)
    }

    /**
     * 清理单次消息发送留下的分组批次缓存。
     * 发送链路在一个批次结束后调用此入口，避免随机复用状态跨消息堆积。
     */
    fun clearBatchSelections(messageIdentity: String) {
        TemplateRuntimeCoordinator.clearBatchSelections(messageIdentity)
    }

    /**
     * 构造最终模板结果。
     * 模板名选择与运行态写入已经下沉到协调层，这里只负责补齐模板正文。
     */
    private fun buildSelectedTemplate(
        type: String,
        scope: String,
        templateName: String,
    ): SelectedTemplate {
        val content = templateContent(type, templateName) ?: fallbackToDefault(type).templateContent
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

