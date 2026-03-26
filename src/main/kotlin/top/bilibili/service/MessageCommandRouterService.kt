package top.bilibili.service

import kotlinx.coroutines.launch
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformInboundMessage
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.ResourceStrictness
import top.bilibili.tasker.DynamicCheckTasker

object MessageCommandRouterService {
    suspend fun handleGroupMessage(event: PlatformInboundMessage) {
        val groupId = event.chatId.toLongOrNull() ?: return
        val userId = event.senderId.toLongOrNull() ?: return
        val message = event.messageText.trim()
        val isSuperAdmin = CommandPermission.isSuperAdmin(userId)

        if (isSuperAdmin && (message == "/login" || message == "登录")) {
            BiliBiliBot.logger.info("收到登录命令，准备发送二维码...")
            launchManaged(operation = "group:/login:$groupId") {
                LoginService.login(isGroup = true, contactId = groupId)
            }
            return
        }

        if (isSuperAdmin && message.startsWith("/add ")) {
            val uid = message.removePrefix("/add ").trim().toLongOrNull()
            if (uid == null) {
                send(groupId, true, "UID 格式错误")
            } else {
                QuickSubscriptionService.subscribe(groupId, uid, isGroup = true)
            }
            return
        }

        if (isSuperAdmin && message.startsWith("/del ")) {
            val uid = message.removePrefix("/del ").trim().toLongOrNull()
            if (uid == null) {
                send(groupId, true, "UID 格式错误")
            } else {
                QuickSubscriptionService.unsubscribe(groupId, uid, isGroup = true)
            }
            return
        }

        if (isSuperAdmin && message == "/list") {
            QuickSubscriptionService.listSubscriptions(groupId, isGroup = true)
            return
        }

        if (isSuperAdmin) {
            when {
                message == "/black list" -> {
                    BlacklistCommandService.quickList(groupId, isGroup = true)
                    return
                }

                message.startsWith("/black ") -> {
                    val targetId = message.removePrefix("/black ").trim().toLongOrNull()
                    if (targetId == null) {
                        send(groupId, true, "QQ号格式错误")
                    } else {
                        BlacklistCommandService.quickAdd(groupId, targetId, isGroup = true)
                    }
                    return
                }

                message.startsWith("/unblock ") -> {
                    val targetId = message.removePrefix("/unblock ").trim().toLongOrNull()
                    if (targetId == null) {
                        send(groupId, true, "QQ号格式错误")
                    } else {
                        BlacklistCommandService.quickRemove(groupId, targetId, isGroup = true)
                    }
                    return
                }
            }
        }

        if (isSuperAdmin && message == "/check") {
            send(groupId, true, "正在检查动态...")
            launchManaged(
                operation = "group:/check:$groupId",
                strictness = ResourceStrictness.RELAXED_LONG_RUNNING,
            ) {
                BusinessLifecycleManager.run(
                    owner = "MessageCommandRouterService",
                    operation = "group:/check:$groupId",
                ) {
                    try {
                        val result = DynamicCheckTasker.executeManualCheck()
                        if (result > 0) {
                            send(groupId, true, "检查完成，检测到 $result 条动态，正在处理...")
                        } else {
                            send(groupId, true, "检查完成，没有新动态")
                        }
                    } catch (e: Exception) {
                        send(groupId, true, "检查失败: ${e.message}")
                        BiliBiliBot.logger.error("手动检查失败", e)
                    }
                }
            }
            return
        }

        if (message.startsWith("/bili ") && (isSuperAdmin || CommandPermission.isGroupAdmin(groupId, userId))) {
            BiliCommandDispatchService.dispatch(groupId, userId, message, isGroup = true)
        }
    }

    suspend fun handlePrivateMessage(event: PlatformInboundMessage) {
        val userId = event.senderId.toLongOrNull() ?: return
        val message = event.messageText.trim()
        val isSuperAdmin = CommandPermission.isSuperAdmin(userId)

        if (isSuperAdmin && (message == "/login" || message == "登录")) {
            BiliBiliBot.logger.info("收到登录命令，准备发送二维码...")
            launchManaged(operation = "private:/login:$userId") {
                LoginService.login(isGroup = false, contactId = userId)
            }
            return
        }

        if (!isSuperAdmin) return

        if (message.startsWith("/add ")) {
            val uid = message.removePrefix("/add ").trim().toLongOrNull()
            if (uid == null) {
                send(userId, false, "UID 格式错误")
            } else {
                QuickSubscriptionService.subscribe(userId, uid, isGroup = false)
            }
            return
        }

        if (message.startsWith("/del ")) {
            val uid = message.removePrefix("/del ").trim().toLongOrNull()
            if (uid == null) {
                send(userId, false, "UID 格式错误")
            } else {
                QuickSubscriptionService.unsubscribe(userId, uid, isGroup = false)
            }
            return
        }

        if (message == "/list") {
            QuickSubscriptionService.listSubscriptions(userId, isGroup = false)
            return
        }

        if (message.startsWith("/bili ")) {
            BiliCommandDispatchService.dispatch(userId, userId, message, isGroup = false)
        }
    }

    private fun launchManaged(
        operation: String,
        strictness: ResourceStrictness = ResourceStrictness.STRICT,
        block: suspend () -> Unit,
    ) {
        BiliBiliBot.launch {
            BusinessLifecycleManager.run(
                owner = "MessageCommandRouterService",
                operation = operation,
                strictness = strictness,
            ) {
                block()
            }
        }
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, text: String) {
        if (isGroup) {
            MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(OutgoingPart.text(text)))
        } else {
            MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(OutgoingPart.text(text)))
        }
    }
}
