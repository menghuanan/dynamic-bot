package top.bilibili.service

import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.api.userInfo
import top.bilibili.connector.OutgoingPart
import top.bilibili.utils.biliClient

object QuickSubscriptionService {
    private val logger = LoggerFactory.getLogger(QuickSubscriptionService::class.java)

    suspend fun subscribe(contactId: Long, uid: Long, isGroup: Boolean) {
        try {
            val contactStr = if (isGroup) "group:$contactId" else "private:$contactId"
            val userInfo = biliClient.userInfo(uid)
            if (userInfo == null) {
                sendText(contactId, isGroup, "无法获取用户信息，UID 可能不存在: $uid")
                return
            }

            val result = DynamicService.addSubscribe(uid, contactStr, isSelf = true)
            BiliConfigManager.saveData()
            sendText(contactId, isGroup, result)
        } catch (e: Exception) {
            logger.error("处理订阅失败: ${e.message}", e)
            sendText(contactId, isGroup, "订阅失败: ${e.message}")
        }
    }

    suspend fun unsubscribe(contactId: Long, uid: Long, isGroup: Boolean) {
        try {
            val contactStr = if (isGroup) "group:$contactId" else "private:$contactId"
            val result = DynamicService.removeSubscribe(uid, contactStr, isSelf = true)
            BiliConfigManager.saveData()
            sendText(contactId, isGroup, result)
        } catch (e: Exception) {
            logger.error("处理取消订阅失败: ${e.message}", e)
            sendText(contactId, isGroup, "取消订阅失败: ${e.message}")
        }
    }

    suspend fun listSubscriptions(contactId: Long, isGroup: Boolean) {
        try {
            val contactStr = if (isGroup) "group:$contactId" else "private:$contactId"
            val subscriptions = top.bilibili.BiliData.dynamic
                .filter { contactStr in it.value.contacts }
                .map { "${it.value.name} (UID: ${it.key})" }

            val msg = if (subscriptions.isEmpty()) "当前没有任何订阅" else "订阅列表:\n${subscriptions.joinToString("\n")}" 
            sendText(contactId, isGroup, msg)
        } catch (e: Exception) {
            logger.error("查询订阅列表失败: ${e.message}", e)
        }
    }

    private suspend fun sendText(contactId: Long, isGroup: Boolean, text: String) {
        if (isGroup) {
            MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(OutgoingPart.text(text)))
        } else {
            MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(OutgoingPart.text(text)))
        }
    }
}
