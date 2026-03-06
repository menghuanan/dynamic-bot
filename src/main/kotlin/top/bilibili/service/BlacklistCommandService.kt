package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.napcat.MessageSegment

object BlacklistCommandService {
    suspend fun handle(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId)) {
            send(contactId, isGroup, "权限不足: 仅超级管理员可使用黑名单功能")
            return
        }

        if (args.size < 2) {
            send(
                contactId,
                isGroup,
                """
                用法:
                /bili blacklist add <QQ号> - 添加到链接解析黑名单
                /bili blacklist remove <QQ号> - 从黑名单移除
                /bili blacklist list - 查看黑名单列表
                """.trimIndent()
            )
            return
        }

        when (args[1].lowercase()) {
            "add" -> add(contactId, args, isGroup)
            "remove", "rm", "del" -> remove(contactId, args, isGroup)
            "list", "ls" -> list(contactId, isGroup)
            else -> send(contactId, isGroup, "未知子命令: ${args[1]}\n使用 /bili blacklist 查看帮助")
        }
    }

    suspend fun quickAdd(contactId: Long, targetId: Long, isGroup: Boolean) {
        if (BiliData.linkParseBlacklist.contains(targetId)) {
            send(contactId, isGroup, "用户 $targetId 已在黑名单中")
            return
        }
        BiliData.linkParseBlacklist.add(targetId)
        BiliConfigManager.saveData()
        send(contactId, isGroup, "已将 $targetId 添加到链接解析黑名单\nBot 将忽略该用户的所有链接解析请求")
    }

    suspend fun quickRemove(contactId: Long, targetId: Long, isGroup: Boolean) {
        if (!BiliData.linkParseBlacklist.contains(targetId)) {
            send(contactId, isGroup, "用户 $targetId 不在黑名单中")
            return
        }
        BiliData.linkParseBlacklist.remove(targetId)
        BiliConfigManager.saveData()
        send(contactId, isGroup, "已将 $targetId 从链接解析黑名单移除")
    }

    suspend fun quickList(contactId: Long, isGroup: Boolean) {
        list(contactId, isGroup)
    }

    private suspend fun add(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            send(contactId, isGroup, "用法: /bili blacklist add <QQ号>")
            return
        }
        val targetId = args[2].toLongOrNull()
        if (targetId == null) {
            send(contactId, isGroup, "QQ号格式错误")
            return
        }
        quickAdd(contactId, targetId, isGroup)
    }

    private suspend fun remove(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            send(contactId, isGroup, "用法: /bili blacklist remove <QQ号>")
            return
        }
        val targetId = args[2].toLongOrNull()
        if (targetId == null) {
            send(contactId, isGroup, "QQ号格式错误")
            return
        }
        quickRemove(contactId, targetId, isGroup)
    }

    private suspend fun list(contactId: Long, isGroup: Boolean) {
        val blacklist = BiliData.linkParseBlacklist
        val msg = if (blacklist.isEmpty()) {
            "链接解析黑名单为空"
        } else {
            "链接解析黑名单 (${blacklist.size} 个用户):\n${blacklist.joinToString("\n")}"
        }
        send(contactId, isGroup, msg)
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, msg: String) {
        if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }
}
