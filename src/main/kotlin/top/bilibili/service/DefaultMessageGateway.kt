package top.bilibili.service

import org.slf4j.Logger
import top.bilibili.connector.CapabilityGuard
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.parsePlatformContact
import top.bilibili.utils.toSubject

/**
 * 默认消息网关实现，在发送入口统一补齐管理员联系人解析和能力守卫。
 */
class DefaultMessageGateway(
    // 网关只保留 connector-owned 发送入口，避免继续注入 raw adapter provider。
    private val sendMessageEntryPoint: suspend (PlatformContact, List<OutgoingPart>) -> Boolean,
    private val adminContactProvider: () -> String?,
    private val logger: Logger,
) : MessageGateway {

    /**
     * 将管理员通知收口到标准联系人发送链路，避免业务层自行处理管理员目标解析。
     */
    override suspend fun sendAdminMessage(message: String): Boolean {
        val adminContact = adminContactProvider()?.let(::parsePlatformContact)
        if (adminContact == null) {
            logger.warn("未配置管理员联系人，无法发送通知")
            return false
        }
        return sendMessageGuarded(adminContact, listOf(OutgoingPart.text(message)))
    }

    /**
     * 保留最薄的底层发送委托，让具体适配器只暴露一个发送入口。
     */
    override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        return sendMessageEntryPoint.invoke(contact, message)
    }

    /**
     * 统一在网关入口做请求级 capability guard，阻断时只返回当前发送失败并输出统一标记。
     */
    override suspend fun sendMessageGuarded(contact: PlatformContact, message: List<OutgoingPart>): Boolean {
        val sendGuard = PlatformCapabilityService.guardMessageSend(contact)
        if (sendGuard.stopCurrentOperation) {
            logGuardStop("send-message", contact, sendGuard)
            return false
        }

        if (message.any { it is OutgoingPart.MentionAll }) {
            val atAllGuard = PlatformCapabilityService.guardAtAllInContact(contact)
            if (atAllGuard.stopCurrentOperation) {
                logGuardStop("at-all", contact, atAllGuard)
                return false
            }
        }

        val imageSources = message.mapNotNull { part ->
            when (part) {
                is OutgoingPart.Image -> part.source
                else -> null
            }
        }
        if (imageSources.isNotEmpty()) {
            val imageGuard = PlatformCapabilityService.guardImageSend(contact, imageSources)
            if (imageGuard.stopCurrentOperation) {
                logGuardStop("send-images", contact, imageGuard)
                return false
            }
        }

        return sendMessage(contact, message)
    }

    private fun logGuardStop(
        operation: String,
        contact: PlatformContact,
        result: CapabilityGuardResult,
    ) {
        logger.warn(
            "{} [{}] {} reason={}",
            result.marker ?: CapabilityGuard.UNSUPPORTED_MESSAGE,
            operation,
            contact.toSubject(),
            when (result) {
                is CapabilityGuardResult.Degraded -> result.reason
                is CapabilityGuardResult.Unsupported -> result.reason
                CapabilityGuardResult.Supported -> "supported"
            },
        )
    }
}
