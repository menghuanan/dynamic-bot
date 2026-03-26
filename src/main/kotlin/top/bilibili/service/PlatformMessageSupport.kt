package top.bilibili.service

import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.toSubject

/**
 * 统一通过平台联系人发送纯文本，避免业务层继续区分群聊/私聊发送分支。
 */
suspend fun sendText(contact: PlatformContact, text: String): Boolean {
    return MessageGatewayProvider.require().sendMessageGuarded(contact, listOf(OutgoingPart.text(text)))
}

/**
 * 统一通过平台联系人发送消息片段集合。
 */
suspend fun sendParts(contact: PlatformContact, parts: List<OutgoingPart>): Boolean {
    return MessageGatewayProvider.require().sendMessageGuarded(contact, parts)
}

/**
 * 在发送前统一评估图片能力；如果当前平台无法直发图片，则显式退回到文本降级内容。
 */
suspend fun sendPartsWithCapabilityFallback(
    contact: PlatformContact,
    parts: List<OutgoingPart>,
    fallbackText: String? = null,
): Boolean {
    val imageSources = parts.mapNotNull { part ->
        when (part) {
            is OutgoingPart.Image -> part.source
            else -> null
        }
    }
    if (imageSources.isEmpty()) {
        return sendParts(contact, parts)
    }

    val imageGuard = runCatching {
        PlatformCapabilityService.guardImageSend(contact, imageSources)
    }.getOrElse {
        CapabilityGuardResult.Unsupported(reason = "image guard failed: ${it.message}")
    }
    when (imageGuard) {
        CapabilityGuardResult.Supported -> return sendParts(contact, parts)
        is CapabilityGuardResult.Unsupported -> return false
        is CapabilityGuardResult.Degraded -> Unit
    }

    // 图片不可发送时只保留明确的文本降级内容，避免业务层自己到处判断平台差异。
    val fallbackParts = buildImageFallbackParts(parts, fallbackText)
    if (fallbackParts.isEmpty()) {
        return false
    }
    return sendParts(contact, fallbackParts)
}

/**
 * 将图片消息收敛为明确的文本降级内容；优先使用调用方提供的专门提示，再回退到原文本片段。
 */
private fun buildImageFallbackParts(parts: List<OutgoingPart>, fallbackText: String?): List<OutgoingPart> {
    if (!fallbackText.isNullOrBlank()) {
        return listOf(OutgoingPart.text(fallbackText))
    }
    return parts.filterIsInstance<OutgoingPart.Text>()
}

/**
 * 为当前会话生成标准化 subject，供订阅/模板/过滤器等持久化逻辑复用。
 */
fun currentSubject(contact: PlatformContact): String = contact.toSubject()
