package top.bilibili.service

import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.api.userInfo
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.biliClient
import top.bilibili.utils.containsEquivalentSubject
import top.bilibili.utils.toSubject

object QuickSubscriptionService {
    private val logger = LoggerFactory.getLogger(QuickSubscriptionService::class.java)

    suspend fun subscribe(contact: PlatformContact, uid: Long) {
        try {
            val contactStr = contact.toSubject()
            val userInfo = biliClient.userInfo(uid)
            if (userInfo == null) {
                sendText(contact, "无法获取用户信息，UID 可能不存在: $uid")
                return
            }

            val result = DynamicService.addSubscribe(uid, contactStr, isSelf = true)
            BiliConfigManager.saveData()
            sendText(contact, result)
        } catch (e: Exception) {
            logger.error("处理订阅失败: ${e.message}", e)
            sendText(contact, "订阅失败: ${e.message}")
        }
    }

    suspend fun unsubscribe(contact: PlatformContact, uid: Long) {
        try {
            val contactStr = contact.toSubject()
            val result = DynamicService.removeSubscribe(uid, contactStr, isSelf = true)
            BiliConfigManager.saveData()
            sendText(contact, result)
        } catch (e: Exception) {
            logger.error("处理取消订阅失败: ${e.message}", e)
            sendText(contact, "取消订阅失败: ${e.message}")
        }
    }

    suspend fun listSubscriptions(contact: PlatformContact) {
        try {
            val contactStr = contact.toSubject()
            val subscriptions = top.bilibili.BiliData.dynamic
                .filter { containsEquivalentSubject(it.value.contacts, contactStr) }
                .map { "${it.value.name} (UID: ${it.key})" }

            val msg = if (subscriptions.isEmpty()) "当前没有任何订阅" else "订阅列表:\n${subscriptions.joinToString("\n")}"
            sendText(contact, msg)
        } catch (e: Exception) {
            logger.error("查询订阅列表失败: ${e.message}", e)
        }
    }
}
