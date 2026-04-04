package top.bilibili.service

import kotlinx.coroutines.launch
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.tasker.DynamicCheckTasker

/**
 * 将入站消息路由到快捷命令和 /bili 命令链，避免平台事件层直接耦合业务实现。
 */
object MessageCommandRouterService {
    /**
     * 处理群聊消息中的快捷命令与管理命令，保持群权限边界集中可控。
     */
    suspend fun handleGroupMessage(event: PlatformInboundMessage) {
        val groupContact = event.chatContact
        val senderContact = event.senderContact
        val message = event.messageText.trim()
        val isSuperAdmin = CommandPermission.isSuperAdmin(senderContact)

        if (isSuperAdmin && (message == "/login" || message == "登录")) {
            BiliBiliBot.logger.info("收到登录命令，准备发送二维码...")
            launchManaged(operation = "group:/login:${groupContact.id}") {
                LoginService.login(groupContact)
            }
            return
        }

        if (isSuperAdmin && message.startsWith("/add ")) {
            val uid = message.removePrefix("/add ").trim().toLongOrNull()
            if (uid == null) {
                sendText(groupContact, "UID 格式错误")
            } else {
                QuickSubscriptionService.subscribe(groupContact, uid)
            }
            return
        }

        if (isSuperAdmin && message.startsWith("/del ")) {
            val uid = message.removePrefix("/del ").trim().toLongOrNull()
            if (uid == null) {
                sendText(groupContact, "UID 格式错误")
            } else {
                QuickSubscriptionService.unsubscribe(groupContact, uid)
            }
            return
        }

        if (isSuperAdmin && message == "/list") {
            QuickSubscriptionService.listSubscriptions(groupContact)
            return
        }

        if (isSuperAdmin) {
            when {
                message == "/black list" -> {
                    BlacklistCommandService.quickList(groupContact)
                    return
                }

                message.startsWith("/black ") -> {
                    val targetId = message.removePrefix("/black ").trim()
                    if (targetId.isBlank()) {
                        sendText(groupContact, "联系人格式错误")
                    } else {
                        BlacklistCommandService.quickAdd(groupContact, targetId)
                    }
                    return
                }

                message.startsWith("/unblock ") -> {
                    val targetId = message.removePrefix("/unblock ").trim()
                    if (targetId.isBlank()) {
                        sendText(groupContact, "联系人格式错误")
                    } else {
                        BlacklistCommandService.quickRemove(groupContact, targetId)
                    }
                    return
                }
            }
        }

        if (isSuperAdmin && message == "/check") {
            sendText(groupContact, "正在检查动态...")
            launchManaged(
                operation = "group:/check:${groupContact.id}",
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
            ) {
                BusinessLifecycleManager.run(
                    owner = "MessageCommandRouterService",
                    operation = "group:/check:${groupContact.id}",
                ) {
                    try {
                        val result = DynamicCheckTasker.executeManualCheck()
                        if (result > 0) {
                            sendText(groupContact, "检查完成，检测到 $result 条动态，正在处理...")
                        } else {
                            sendText(groupContact, "检查完成，没有新动态")
                        }
                    } catch (e: Exception) {
                        sendText(groupContact, "检查失败: ${e.message}")
                        BiliBiliBot.logger.error("手动检查失败", e)
                    }
                }
            }
            return
        }

        if (message.startsWith("/bili ") && (isSuperAdmin || CommandPermission.isGroupAdmin(groupContact, senderContact))) {
            BiliCommandDispatchService.dispatch(groupContact, senderContact, message)
        }
    }

    /**
     * 处理私聊中的管理命令，只在超级管理员上下文开放完整能力。
     */
    suspend fun handlePrivateMessage(event: PlatformInboundMessage) {
        val userContact = event.chatContact
        val senderContact = event.senderContact
        val message = event.messageText.trim()
        val isSuperAdmin = CommandPermission.isSuperAdmin(senderContact)

        if (isSuperAdmin && (message == "/login" || message == "登录")) {
            BiliBiliBot.logger.info("收到登录命令，准备发送二维码...")
            launchManaged(operation = "private:/login:${userContact.id}") {
                LoginService.login(userContact)
            }
            return
        }

        if (!isSuperAdmin) return

        if (message.startsWith("/add ")) {
            val uid = message.removePrefix("/add ").trim().toLongOrNull()
            if (uid == null) {
                sendText(userContact, "UID 格式错误")
            } else {
                QuickSubscriptionService.subscribe(userContact, uid)
            }
            return
        }

        if (message.startsWith("/del ")) {
            val uid = message.removePrefix("/del ").trim().toLongOrNull()
            if (uid == null) {
                sendText(userContact, "UID 格式错误")
            } else {
                QuickSubscriptionService.unsubscribe(userContact, uid)
            }
            return
        }

        if (message == "/list") {
            QuickSubscriptionService.listSubscriptions(userContact)
            return
        }

        if (message.startsWith("/bili ")) {
            BiliCommandDispatchService.dispatch(userContact, senderContact, message)
        }
    }

    private fun launchManaged(
        operation: String,
        strictness: ResourceStrictness = ResourceStrictness.STRICT,
        block: suspend () -> Unit,
    ) {
        // 停机期间不再调度新的异步命令任务，避免资源回收阶段继续拉起业务协程。
        if (BiliBiliBot.isStopping()) {
            BiliBiliBot.logger.info("停机期间忽略命令异步任务: $operation")
            return
        }
        BiliBiliBot.launch {
            // 调度到根作用域后再次校验停机状态，覆盖停机信号与协程实际启动之间的竞态窗口。
            if (BiliBiliBot.isStopping()) {
                BiliBiliBot.logger.info("停机期间忽略命令异步任务: $operation")
                return@launch
            }
            BusinessLifecycleManager.run(
                owner = "MessageCommandRouterService",
                operation = operation,
                strictness = strictness,
            ) {
                block()
            }
        }
    }
}
