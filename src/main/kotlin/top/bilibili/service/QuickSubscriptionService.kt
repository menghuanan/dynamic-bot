package top.bilibili.service

import org.slf4j.LoggerFactory
import top.bilibili.BiliConfigManager
import top.bilibili.api.userInfo
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.biliClient
import top.bilibili.utils.containsEquivalentSubject
import top.bilibili.utils.toSubject

/**
 * 提供 /add /del /list 等快捷命令入口，避免消息路由直接操作订阅存储。
 */
object QuickSubscriptionService {
    private val logger = LoggerFactory.getLogger(QuickSubscriptionService::class.java)

    /**
     * 为当前会话快速订阅指定 UID，并在入口处先校验用户是否存在。
     */
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

    /**
     * 为当前会话快速取消指定 UID 的订阅，统一复用标准取消链路。
     */
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

    /**
     * 列出当前会话命中的动态订阅，方便快捷命令直接查看结果。
     */
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
