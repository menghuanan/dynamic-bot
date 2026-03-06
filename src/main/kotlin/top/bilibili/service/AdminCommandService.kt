package top.bilibili.service

import top.bilibili.config.ConfigManager
import top.bilibili.config.GroupAdminConfig
import top.bilibili.napcat.MessageSegment

object AdminCommandService {
    suspend fun handle(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId)) return

        if (args.size < 2) {
            send(
                contactId,
                isGroup,
                """
                用法:
                /bili admin add <QQ号> - 添加本群普通管理员
                /bili admin remove <QQ号> - 移除本群普通管理员
                /bili admin list - 查询本群管理员
                /bili admin all - 查询全部普通管理员
                """.trimIndent()
            )
            return
        }

        when (args[1].lowercase()) {
            "add" -> add(contactId, args, isGroup)
            "remove", "rm" -> remove(contactId, args, isGroup)
            "list", "ls" -> list(contactId, isGroup)
            "all" -> all(contactId, isGroup)
            else -> send(contactId, isGroup, "未知子命令: ${args[1]}")
        }
    }

    private suspend fun add(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (!isGroup) {
            send(contactId, false, "请在群聊中使用此命令")
            return
        }
        if (args.size < 3) {
            send(contactId, true, "用法: /bili admin add <QQ号>")
            return
        }

        val targetId = args[2].toLongOrNull()
        if (targetId == null) {
            send(contactId, true, "QQ号格式错误")
            return
        }

        val admins = ConfigManager.botConfig.admins
        var groupConfig = admins.find { it.groupId == contactId }
        if (groupConfig == null) {
            groupConfig = GroupAdminConfig(contactId)
            admins.add(groupConfig)
        }

        if (targetId in groupConfig.userIds) {
            send(contactId, true, "用户 $targetId 已经是本群管理员")
            return
        }

        groupConfig.userIds.add(targetId)
        ConfigManager.saveConfig()
        send(contactId, true, "已将 $targetId 添加为本群普通管理员")
    }

    private suspend fun remove(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (!isGroup) {
            send(contactId, false, "请在群聊中使用此命令")
            return
        }
        if (args.size < 3) {
            send(contactId, true, "用法: /bili admin remove <QQ号>")
            return
        }

        val targetId = args[2].toLongOrNull()
        if (targetId == null) {
            send(contactId, true, "QQ号格式错误")
            return
        }

        val groupConfig = ConfigManager.botConfig.admins.find { it.groupId == contactId }
        if (groupConfig == null || targetId !in groupConfig.userIds) {
            send(contactId, true, "用户 $targetId 不是本群管理员")
            return
        }

        groupConfig.userIds.remove(targetId)
        if (groupConfig.userIds.isEmpty()) {
            ConfigManager.botConfig.admins.remove(groupConfig)
        }
        ConfigManager.saveConfig()
        send(contactId, true, "已移除 $targetId 的本群普通管理员身份")
    }

    private suspend fun list(contactId: Long, isGroup: Boolean) {
        if (!isGroup) {
            send(contactId, false, "请在群聊中使用此命令")
            return
        }
        val groupConfig = ConfigManager.botConfig.admins.find { it.groupId == contactId }
        if (groupConfig == null || groupConfig.userIds.isEmpty()) {
            send(contactId, true, "本群暂无普通管理员")
        } else {
            send(contactId, true, "本群普通管理员: ${groupConfig.userIds.joinToString("、")}")
        }
    }

    private suspend fun all(contactId: Long, isGroup: Boolean) {
        val admins = ConfigManager.botConfig.admins
        if (admins.isEmpty()) {
            send(contactId, isGroup, "暂无任何普通管理员")
            return
        }

        val sb = StringBuilder()
        admins.forEach { cfg ->
            if (cfg.userIds.isNotEmpty()) {
                sb.append("群聊: ${cfg.groupId}\n")
                sb.append("普通管理员: ${cfg.userIds.joinToString("、")}\n")
            }
        }
        send(contactId, isGroup, sb.toString().trim())
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, msg: String) {
        if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }
}
