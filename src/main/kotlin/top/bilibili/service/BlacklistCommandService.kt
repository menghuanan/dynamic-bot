package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.parseCommandPlatformContact
import top.bilibili.utils.toSubject

object BlacklistCommandService {
    suspend fun handle(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        if (!CommandPermission.isSuperAdmin(senderContact)) {
            sendText(chatContact, "权限不足: 仅超级管理员可使用黑名单功能")
            return
        }

        if (args.size < 2) {
            sendText(
                chatContact,
                """
                用法:
                /bili blacklist add <联系人> - 添加到链接解析黑名单
                /bili blacklist remove <联系人> - 从黑名单移除
                /bili blacklist list - 查看黑名单列表
                """.trimIndent()
            )
            return
        }

        when (args[1].lowercase()) {
            "add" -> add(chatContact, args)
            "remove", "rm", "del" -> remove(chatContact, args)
            "list", "ls" -> list(chatContact)
            else -> sendText(chatContact, "未知子命令: ${args[1]}\n使用 /bili blacklist 查看帮助")
        }
    }

    suspend fun quickAdd(chatContact: PlatformContact, targetId: String) {
        val normalizedTarget = normalizeTarget(chatContact, targetId)
        if (BiliData.linkParseBlacklistContacts.contains(normalizedTarget)) {
            sendText(chatContact, "用户 $targetId 已在黑名单中")
            return
        }
        BiliData.linkParseBlacklistContacts.add(normalizedTarget)
        BiliConfigManager.saveData()
        sendText(chatContact, "已将 $targetId 添加到链接解析黑名单\nBot 将忽略该用户的所有链接解析请求")
    }

    suspend fun quickRemove(chatContact: PlatformContact, targetId: String) {
        val normalizedTarget = normalizeTarget(chatContact, targetId)
        if (!BiliData.linkParseBlacklistContacts.contains(normalizedTarget)) {
            sendText(chatContact, "用户 $targetId 不在黑名单中")
            return
        }
        BiliData.linkParseBlacklistContacts.remove(normalizedTarget)
        BiliConfigManager.saveData()
        sendText(chatContact, "已将 $targetId 从链接解析黑名单移除")
    }

    suspend fun quickList(chatContact: PlatformContact) {
        list(chatContact)
    }

    private suspend fun add(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 3) {
            sendText(chatContact, "用法: /bili blacklist add <联系人>")
            return
        }
        val targetId = args[2].trim()
        if (targetId.isBlank()) {
            sendText(chatContact, "联系人格式错误")
            return
        }
        quickAdd(chatContact, targetId)
    }

    private suspend fun remove(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 3) {
            sendText(chatContact, "用法: /bili blacklist remove <联系人>")
            return
        }
        val targetId = args[2].trim()
        if (targetId.isBlank()) {
            sendText(chatContact, "联系人格式错误")
            return
        }
        quickRemove(chatContact, targetId)
    }

    private suspend fun list(chatContact: PlatformContact) {
        val blacklist = BiliData.linkParseBlacklistContacts.toList().sorted()
        val msg = if (blacklist.isEmpty()) {
            "链接解析黑名单为空"
        } else {
            "链接解析黑名单 (${blacklist.size} 个用户):\n${blacklist.joinToString("\n")}"
        }
        sendText(chatContact, msg)
    }

    /**
     * 将黑名单目标统一收口为 subject；未显式带命名空间时按当前平台私聊用户处理。
     */
    private fun normalizeTarget(chatContact: PlatformContact, targetId: String): String {
        val contact = parseCommandPlatformContact(
            raw = targetId,
            defaultPlatform = chatContact.platform,
            defaultType = PlatformChatType.PRIVATE,
        ) ?: PlatformContact(chatContact.platform, PlatformChatType.PRIVATE, targetId.trim())
        return contact.toSubject()
    }
}
