package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.core.BiliBiliBot
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.actionNotify
import top.bilibili.utils.findLocalIdOrName
import top.bilibili.utils.toSubject

object SettingsCommandService {
    /**
     * 统一收敛 atall 子命令解析，兼容旧 set 别名和“/bili atall <类型> <uid>”旧式快捷写法。
     */
    internal data class AtAllParsedCommand(
        val action: String,
        val type: String? = null,
        val uidArg: String? = null,
        val rawCommand: String,
    )

    /**
     * 仅在 atall 命令入口把原始参数归一化，后续执行链统一按 add/del/list 三类分支处理。
     */
    internal fun parseAtAllCommandArgs(args: List<String>): AtAllParsedCommand? {
        val rawCommand = args.getOrNull(1)?.trim()?.lowercase() ?: return null
        return when (rawCommand) {
            "add", "set" -> AtAllParsedCommand("add", args.getOrNull(2), args.getOrNull(3), rawCommand)
            "del", "remove", "rm" -> AtAllParsedCommand("del", args.getOrNull(2), args.getOrNull(3), rawCommand)
            "list", "ls" -> AtAllParsedCommand("list", uidArg = args.getOrNull(2), rawCommand = rawCommand)
            else -> {
                if (AtAllService.supportsType(rawCommand)) {
                    AtAllParsedCommand("add", rawCommand, args.getOrNull(2), rawCommand)
                } else {
                    AtAllParsedCommand("unknown", rawCommand = rawCommand)
                }
            }
        }
    }

    suspend fun handleAtAll(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        val isGroup = chatContact.type == PlatformChatType.GROUP
        if (!CommandPermission.isSuperAdmin(senderContact) && (!isGroup || !CommandPermission.isGroupAdmin(chatContact, senderContact))) return
        val subject = chatContact.toSubject()

        if (args.size < 2) {
            sendText(chatContact, """
                用法:
                /bili atall add <类型> <uid>
                /bili atall <类型> <uid> 兼容旧写法，等价于 add
                /bili atall del <类型> <uid>
                /bili atall list [uid]
            """.trimIndent())
            return
        }

        val parsedCommand = parseAtAllCommandArgs(args)
        if (parsedCommand == null) {
            sendText(chatContact, """
                用法:
                /bili atall add <类型> <uid>
                /bili atall <类型> <uid> 兼容旧写法，等价于 add
                /bili atall del <类型> <uid>
                /bili atall list [uid]
            """.trimIndent())
            return
        }

        val command = parsedCommand.action
        val result = when (command) {
            "add" -> {
                val type = parsedCommand.type
                if (type == null) {
                    "用法: /bili atall add <类型> <uid>"
                } else {
                    val uid = parseUidArg(parsedCommand.uidArg)
                    if (uid == null) {
                        sendText(chatContact, "UID 格式错误，请输入纯数字")
                        return
                    }
                    if (!isGroup) {
                        sendText(chatContact, "仅群聊支持 @全体 策略")
                        return
                    }
                    if (!canAtAllInGroup(chatContact)) {
                        sendText(chatContact, "Bot 在该群没有 @全体 权限，未写入配置")
                        return
                    }
                    AtAllService.addAtAll(type, uid, subject)
                }
            }
            "del", "remove", "rm" -> {
                val type = parsedCommand.type
                if (type == null) {
                    "用法: /bili atall del <类型> <uid>"
                } else {
                    val uid = parseUidArg(parsedCommand.uidArg)
                    if (uid == null) {
                        sendText(chatContact, "UID 格式错误，请输入纯数字")
                        return
                    }
                    AtAllService.delAtAll(type, uid, subject)
                }
            }
            "list" -> {
                val rawUid = parsedCommand.uidArg
                val uid = if (rawUid == null) {
                    0L
                } else {
                    val parsedUid = parseUidArg(rawUid)
                    if (parsedUid == null) {
                        sendText(chatContact, "UID 格式错误，请输入纯数字")
                        return
                    }
                    parsedUid
                }
                AtAllService.listAtAll(uid, subject)
            }
            else -> "未知艾特全体命令: ${parsedCommand.rawCommand}"
        }

        if ((command == "add" || command == "del") &&
            (result == "添加成功" || result == "删除成功")
        ) {
            BiliConfigManager.saveData()
        }
        sendText(chatContact, result)
    }

    suspend fun handleConfig(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        val isGroup = chatContact.type == PlatformChatType.GROUP
        if (!CommandPermission.isSuperAdmin(senderContact) && (!isGroup || !CommandPermission.isGroupAdmin(chatContact, senderContact))) return
        if (args.getOrNull(1)?.equals("color", ignoreCase = true) == true) {
            handleConfigColor(chatContact, senderContact, args)
            return
        }
        val subject = chatContact.toSubject()
        val uid = if (args.size <= 1) {
            0L
        } else {
            val parsedUid = parseUidArg(args[1])
            if (parsedUid == null) {
                sendText(chatContact, "UID 格式错误，请输入纯数字")
                return
            }
            parsedUid
        }
        sendText(chatContact, ConfigService.configOverview(uid, subject))
    }

    suspend fun handleColor(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        if (!CommandPermission.isSuperAdmin(senderContact)) {
            sendText(chatContact, "权限不足: 仅超级管理员可用")
            return
        }
        if (args.size < 3) {
            sendText(chatContact, """
                用法:
                /bili color <uid|用户名> <HEX颜色>
                /bili config color <uid|用户名> <HEX颜色>
            """.trimIndent())
            return
        }
        val userArg = args[1]
        val colorArg = args[2].trim()
        applyColorChange(chatContact, userArg, colorArg)
    }

    private suspend fun handleConfigColor(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        if (!CommandPermission.isSuperAdmin(senderContact)) {
            sendText(chatContact, "权限不足: 仅超级管理员可用")
            return
        }
        if (args.size < 4) {
            sendText(chatContact, """
                用法:
                /bili config color <uid|用户名> <HEX颜色>
                /bili color <uid|用户名> <HEX颜色>
            """.trimIndent())
            return
        }
        val userArg = args[2]
        val colorArg = args[3].trim()
        applyColorChange(chatContact, userArg, colorArg)
    }

    private suspend fun applyColorChange(chatContact: PlatformContact, userArg: String, colorArg: String) {
        val subject = chatContact.toSubject()
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
                sendText(chatContact, failureMessage)
                actionNotify("主题色持久化失败: user=$userArg, color=$colorArg")
                return
            }
        }
        sendText(chatContact, result.message)
    }

    private fun parseUidArg(raw: String?): Long? {
        val text = raw?.trim() ?: return null
        val uid = text.toLongOrNull() ?: return null
        return if (uid > 0L) uid else null
    }

    private suspend fun canAtAllInGroup(groupContact: PlatformContact): Boolean {
        return runCatching {
            PlatformCapabilityService.canAtAllInContact(groupContact)
        }.getOrElse {
            BiliBiliBot.logger.warn("检查群 @全体 权限失败: ${it.message}")
            false
        }
    }
}


