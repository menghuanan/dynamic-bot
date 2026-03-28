package top.bilibili.service

import top.bilibili.config.ConfigManager
import top.bilibili.config.GroupAdminConfig
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.parseCommandPlatformContact
import top.bilibili.utils.subjectsEquivalent
import top.bilibili.utils.toSubject

/**
 * 收敛普通管理员命令入口，避免群管理员配置分散在消息路由里。
 */
object AdminCommandService {
    /**
     * 统一处理普通管理员增删查命令，保证权限校验和帮助文案走同一入口。
     */
    suspend fun handle(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        if (!CommandPermission.isSuperAdmin(senderContact)) return
        val isGroup = chatContact.type == PlatformChatType.GROUP

        if (args.size < 2) {
            sendText(
                chatContact,
                """
                用法:
                /bili admin add <联系人> - 添加本群普通管理员
                /bili admin remove <联系人> - 移除本群普通管理员
                /bili admin list - 查询本群管理员
                /bili admin all - 查询全部普通管理员
                """.trimIndent()
            )
            return
        }

        when (args[1].lowercase()) {
            "add" -> add(chatContact, args)
            "remove", "rm" -> remove(chatContact, args)
            "list", "ls" -> list(chatContact)
            "all" -> all(chatContact)
            else -> sendText(chatContact, "未知子命令: ${args[1]}")
        }
    }

    private suspend fun add(chatContact: PlatformContact, args: List<String>) {
        if (chatContact.type != PlatformChatType.GROUP) {
            sendText(chatContact, "请在群聊中使用此命令")
            return
        }
        if (args.size < 3) {
            sendText(chatContact, "用法: /bili admin add <联系人>")
            return
        }

        val targetContact = parseCommandPlatformContact(
            raw = args[2],
            defaultPlatform = chatContact.platform,
            defaultType = PlatformChatType.PRIVATE,
        )
        if (targetContact == null) {
            sendText(chatContact, "联系人格式错误")
            return
        }

        val admins = ConfigManager.botConfig.admins
        var groupConfig = admins.find { subjectsEquivalent(it.normalizedGroupContact(), chatContact.toSubject()) }
        if (groupConfig == null) {
            groupConfig = GroupAdminConfig(groupContact = chatContact.toSubject())
            admins.add(groupConfig)
        }

        if (groupConfig.normalizedUserContacts().any { subjectsEquivalent(it, targetContact.toSubject()) }) {
            sendText(chatContact, "用户 ${targetContact.id} 已经是本群管理员")
            return
        }

        groupConfig.userContacts.add(targetContact.toSubject())
        ConfigManager.saveConfig()
        sendText(chatContact, "已将 ${targetContact.id} 添加为本群普通管理员")
    }

    private suspend fun remove(chatContact: PlatformContact, args: List<String>) {
        if (chatContact.type != PlatformChatType.GROUP) {
            sendText(chatContact, "请在群聊中使用此命令")
            return
        }
        if (args.size < 3) {
            sendText(chatContact, "用法: /bili admin remove <联系人>")
            return
        }

        val targetContact = parseCommandPlatformContact(
            raw = args[2],
            defaultPlatform = chatContact.platform,
            defaultType = PlatformChatType.PRIVATE,
        )
        if (targetContact == null) {
            sendText(chatContact, "联系人格式错误")
            return
        }

        val groupConfig = ConfigManager.botConfig.admins.find { subjectsEquivalent(it.normalizedGroupContact(), chatContact.toSubject()) }
        if (groupConfig == null || groupConfig.normalizedUserContacts().none { subjectsEquivalent(it, targetContact.toSubject()) }) {
            sendText(chatContact, "用户 ${targetContact.id} 不是本群管理员")
            return
        }

        groupConfig.userContacts.removeIf { subjectsEquivalent(it, targetContact.toSubject()) }
        targetContact.id.toLongOrNull()?.let(groupConfig.userIds::remove)
        if (groupConfig.userIds.isEmpty() && groupConfig.userContacts.isEmpty()) {
            ConfigManager.botConfig.admins.remove(groupConfig)
        }
        ConfigManager.saveConfig()
        sendText(chatContact, "已移除 ${targetContact.id} 的本群普通管理员身份")
    }

    private suspend fun list(chatContact: PlatformContact) {
        if (chatContact.type != PlatformChatType.GROUP) {
            sendText(chatContact, "请在群聊中使用此命令")
            return
        }
        val groupConfig = ConfigManager.botConfig.admins.find { subjectsEquivalent(it.normalizedGroupContact(), chatContact.toSubject()) }
        val admins = groupConfig?.normalizedUserContacts().orEmpty()
        if (groupConfig == null || admins.isEmpty()) {
            sendText(chatContact, "本群暂无普通管理员")
        } else {
            sendText(chatContact, "本群普通管理员: ${admins.joinToString("、")}")
        }
    }

    private suspend fun all(chatContact: PlatformContact) {
        val admins = ConfigManager.botConfig.admins
        if (admins.isEmpty()) {
            sendText(chatContact, "暂无任何普通管理员")
            return
        }

        val sb = StringBuilder()
        admins.forEach { cfg ->
            val users = cfg.normalizedUserContacts()
            if (users.isNotEmpty()) {
                sb.append("群聊: ${cfg.normalizedGroupContact()}\n")
                sb.append("普通管理员: ${users.joinToString("、")}\n")
            }
        }
        sendText(chatContact, sb.toString().trim())
    }
}
