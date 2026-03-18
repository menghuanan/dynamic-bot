package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.core.BiliBiliBot
import top.bilibili.napcat.MessageSegment
import top.bilibili.utils.actionNotify
import top.bilibili.utils.findLocalIdOrName

object SettingsCommandService {
    suspend fun handleAtAll(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId) && (!isGroup || !CommandPermission.isGroupAdmin(contactId, userId))) return
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"

        if (args.size < 2) {
            send(contactId, isGroup, """
                用法:
                /bili atall add <类型> <uid>
                /bili atall del <类型> <uid>
                /bili atall list [uid]
            """.trimIndent())
            return
        }

        val command = args[1].lowercase()
        val result = when (command) {
            "add" -> {
                val type = args.getOrNull(2)
                if (type == null) {
                    "用法: /bili atall add <类型> <uid>"
                } else {
                    val uid = parseUidArg(args.getOrNull(3)) ?: return send(contactId, isGroup, "UID 格式错误，请输入纯数字")
                    if (!isGroup) return send(contactId, isGroup, "仅群聊支持 @全体 策略")
                    if (!canAtAllInGroup(contactId)) {
                        return send(contactId, isGroup, "Bot 在该群没有 @全体 权限，未写入配置")
                    }
                    AtAllService.addAtAll(type, uid, subject)
                }
            }
            "del", "remove", "rm" -> {
                val type = args.getOrNull(2)
                if (type == null) {
                    "用法: /bili atall del <类型> <uid>"
                } else {
                    val uid = parseUidArg(args.getOrNull(3)) ?: return send(contactId, isGroup, "UID 格式错误，请输入纯数字")
                    AtAllService.delAtAll(type, uid, subject)
                }
            }
            "list", "ls" -> {
                val rawUid = args.getOrNull(2)
                val uid = if (rawUid == null) 0L else parseUidArg(rawUid) ?: return send(contactId, isGroup, "UID 格式错误，请输入纯数字")
                AtAllService.listAtAll(uid, subject)
            }
            else -> "未知艾特全体命令: ${args[1]}"
        }

        if ((command == "add" || command == "del" || command == "remove" || command == "rm") &&
            (result == "添加成功" || result == "删除成功")
        ) {
            BiliConfigManager.saveData()
        }
        send(contactId, isGroup, result)
    }

    suspend fun handleConfig(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId) && (!isGroup || !CommandPermission.isGroupAdmin(contactId, userId))) return
        if (args.getOrNull(1)?.equals("color", ignoreCase = true) == true) {
            handleConfigColor(contactId, userId, args, isGroup)
            return
        }
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"
        val uid = if (args.size <= 1) {
            0L
        } else {
            parseUidArg(args[1]) ?: return send(contactId, isGroup, "UID 格式错误，请输入纯数字")
        }
        send(contactId, isGroup, ConfigService.configOverview(uid, subject))
    }

    suspend fun handleColor(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId)) {
            send(contactId, isGroup, "权限不足: 仅超级管理员可用")
            return
        }
        if (args.size < 3) {
            send(contactId, isGroup, """
                用法:
                /bili color <uid|用户名> <HEX颜色>
                /bili config color <uid|用户名> <HEX颜色>
            """.trimIndent())
            return
        }
        val userArg = args[1]
        val colorArg = args[2].trim()
        applyColorChange(contactId, isGroup, userArg, colorArg)
    }

    private suspend fun handleConfigColor(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId)) {
            send(contactId, isGroup, "权限不足: 仅超级管理员可用")
            return
        }
        if (args.size < 4) {
            send(contactId, isGroup, """
                用法:
                /bili config color <uid|用户名> <HEX颜色>
                /bili color <uid|用户名> <HEX颜色>
            """.trimIndent())
            return
        }
        val userArg = args[2]
        val colorArg = args[3].trim()
        applyColorChange(contactId, isGroup, userArg, colorArg)
    }

    private suspend fun applyColorChange(contactId: Long, isGroup: Boolean, userArg: String, colorArg: String) {
        val subject = if (isGroup) "group:$contactId" else "private:$contactId"
        val matches = findLocalIdOrName(userArg)
        val result = when {
            matches.isEmpty() -> ColorBindingResult.failure("未匹配到用户哦")
            matches.size > 1 -> ColorBindingResult.failure(buildString {
                appendLine("有多个匹配项：")
                matches.forEach {
                    appendLine("${BiliConfigManager.data.dynamic[it.first]?.name}: ${it.second}")
                }
            })
            else -> DynamicService.setColor(matches.first().first, subject, colorArg)
        }
        if (result.success) {
            val saved = BiliConfigManager.saveData()
            if (!saved) {
                val failureMessage = "设置成功，但持久化失败，请联系管理员检查日志"
                send(contactId, isGroup, failureMessage)
                actionNotify("主题色持久化失败: user=$userArg, color=$colorArg")
                return
            }
        }
        send(contactId, isGroup, result.message)
    }

    private fun parseUidArg(raw: String?): Long? {
        val text = raw?.trim() ?: return null
        val uid = text.toLongOrNull() ?: return null
        return if (uid > 0L) uid else null
    }

    private suspend fun canAtAllInGroup(groupId: Long): Boolean {
        if (!BiliBiliBot.isNapCatInitialized()) return false
        return runCatching {
            BiliBiliBot.napCat.canAtAllInGroup(groupId)
        }.getOrElse {
            BiliBiliBot.logger.warn("检查群 @全体 权限失败: ${it.message}")
            false
        }
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, msg: String) {
        if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }
}


