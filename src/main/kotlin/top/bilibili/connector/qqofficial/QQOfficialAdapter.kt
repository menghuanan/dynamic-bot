package top.bilibili.connector.qqofficial

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.slf4j.LoggerFactory
import top.bilibili.config.QQOfficialConfig
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformAdapter
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.core.ContactId

class QQOfficialAdapter(
    private val config: QQOfficialConfig = QQOfficialConfig(),
) : PlatformAdapter {
    private val logger = LoggerFactory.getLogger(QQOfficialAdapter::class.java)
    private var started = false

    override val eventFlow: Flow<PlatformInboundMessage> = emptyFlow()

    override fun start() {
        started = true
        if (hasCredentials()) {
            logger.info("QQ 官方适配器骨架已使用配置凭据启动，消息收发尚未实现")
        } else {
            logger.warn("QQ 官方适配器骨架已在未配置凭据时启动，消息收发尚未实现")
        }
    }

    override fun stop() {
        started = false
    }

    override suspend fun sendGroupMessage(groupId: Long, message: List<OutgoingPart>): Boolean {
        logger.debug("QQ 官方适配器骨架丢弃了群消息 $groupId，消息收发尚未实现")
        return false
    }

    override suspend fun sendPrivateMessage(userId: Long, message: List<OutgoingPart>): Boolean {
        logger.debug("QQ 官方适配器骨架丢弃了私聊消息 $userId，消息收发尚未实现")
        return false
    }

    override suspend fun sendMessage(contact: ContactId, message: List<OutgoingPart>): Boolean {
        logger.debug("QQ 官方适配器骨架丢弃了消息 {}，消息收发尚未实现", contact)
        return false
    }

    override fun runtimeStatus(): PlatformRuntimeStatus {
        return PlatformRuntimeStatus(
            connected = false,
            reconnectAttempts = if (started) 0 else 0,
            sendQueueFull = false,
        )
    }

    override suspend fun isGroupReachable(groupId: Long): Boolean = false

    override suspend fun canAtAll(groupId: Long): Boolean = false

    private fun hasCredentials(): Boolean {
        return config.appId.isNotBlank() &&
            config.appSecret.isNotBlank() &&
            config.botToken.isNotBlank()
    }
}
