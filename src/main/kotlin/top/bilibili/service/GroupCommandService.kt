package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.Group
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.containsEquivalentSubject
import top.bilibili.utils.groupLabelFromSubject
import top.bilibili.utils.parseCommandPlatformContact
import top.bilibili.utils.toSubject

object GroupCommandService {
    suspend fun handle(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        if (!CommandPermission.isSuperAdmin(senderContact)) return

        if (args.size < 2) {
            sendText(chatContact, "用法: /bili group <create|delete|add|remove|list|subscribe|unsubscribe>")
            return
        }

        when (args[1].lowercase()) {
            "create" -> create(chatContact, senderContact, args)
            "delete", "del" -> delete(chatContact, args)
            "add" -> add(chatContact, args)
            "remove", "rm" -> remove(chatContact, args)
            "list", "ls" -> list(chatContact, senderContact, args)
            "subscribe", "sub" -> subscribe(chatContact, args)
            "unsubscribe", "unsub" -> unsubscribe(chatContact, args)
            else -> sendText(chatContact, "未知子命令: ${args[1]}")
        }
    }

    suspend fun listGroups(chatContact: PlatformContact, senderContact: PlatformContact) {
        if (!CommandPermission.isSuperAdmin(senderContact)) return
        val groups = BiliData.group
        val msg = if (groups.isEmpty()) {
            "当前没有任何分组"
        } else {
            val groupList = groups.map { (name, group) -> "$name (${group.contacts.size} 个群)" }.joinToString("\n")
            "分组列表:\n$groupList\n\n使用 /bili group list <分组名> 查看详情"
        }
        sendText(chatContact, msg)
    }

    private suspend fun create(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        if (args.size < 3) {
            sendText(chatContact, "用法: /bili group create <分组名>")
            return
        }

        val groupName = args[2]
        if (groupName in BiliData.group) {
            sendText(chatContact, "分组 $groupName 已存在")
            return
        }

        val creatorId = senderContact.id.toLongOrNull() ?: 0L
        BiliData.group[groupName] = Group(
            name = groupName,
            creator = creatorId,
            admin = creatorId.takeIf { it > 0L }?.let { mutableSetOf(it) } ?: mutableSetOf(),
            creatorContact = senderContact.toSubject(),
            adminContacts = mutableSetOf(senderContact.toSubject()),
            contacts = mutableSetOf(),
        )
        BiliConfigManager.saveData()
        sendText(chatContact, "成功创建分组: $groupName")
    }

    private suspend fun delete(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 3) {
            sendText(chatContact, "用法: /bili group delete <分组名>")
            return
        }
        val groupName = args[2]
        if (groupName !in BiliData.group) {
            sendText(chatContact, "分组 $groupName 不存在")
            return
        }

        DynamicService.deleteGroupRef(groupName)
        BiliData.group.remove(groupName)
        BiliConfigManager.saveData()
        sendText(chatContact, "成功删除分组: $groupName")
    }

    private suspend fun add(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 4) {
            sendText(chatContact, "用法: /bili group add <分组名> <群号>")
            return
        }
        val groupName = args[2]
        val targetContact = parseCommandPlatformContact(args[3], chatContact.platform, PlatformChatType.GROUP)
        if (targetContact == null) {
            sendText(chatContact, "群号格式错误")
            return
        }
        val group = BiliData.group[groupName]
        if (group == null) {
            sendText(chatContact, "分组 $groupName 不存在")
            return
        }

        val inGroup = runCatching {
            PlatformCapabilityService.canSendMessageTo(targetContact)
        }.getOrDefault(false)
        if (!inGroup) {
            sendText(chatContact, "拒绝添加：Bot 不在目标群 ${targetContact.id}")
            return
        }

        val contactStr = targetContact.toSubject()
        if (containsEquivalentSubject(group.contacts, contactStr)) {
            sendText(chatContact, "群 ${targetContact.id} 已在分组 $groupName 中")
            return
        }
        group.contacts.add(contactStr)
        DynamicService.refreshGroupRef(groupName)
        BiliConfigManager.saveData()
        sendText(chatContact, "成功将群 ${targetContact.id} 加入分组 $groupName")
    }

    private suspend fun remove(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 4) {
            sendText(chatContact, "用法: /bili group remove <分组名> <群号>")
            return
        }
        val groupName = args[2]
        val targetContact = parseCommandPlatformContact(args[3], chatContact.platform, PlatformChatType.GROUP)
        if (targetContact == null) {
            sendText(chatContact, "群号格式错误")
            return
        }
        val group = BiliData.group[groupName]
        if (group == null) {
            sendText(chatContact, "分组 $groupName 不存在")
            return
        }
        val contactStr = targetContact.toSubject()
        if (!containsEquivalentSubject(group.contacts, contactStr)) {
            sendText(chatContact, "群 ${targetContact.id} 不在分组 $groupName 中")
            return
        }
        group.contacts.remove(contactStr)
        DynamicService.refreshGroupRef(groupName)
        BiliConfigManager.saveData()
        sendText(chatContact, "成功从分组 $groupName 移除群 ${targetContact.id}")
    }

    private suspend fun list(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        if (args.size < 3) {
            listGroups(chatContact, senderContact)
            return
        }
        val groupName = args[2]
        val group = BiliData.group[groupName]
        if (group == null) {
            sendText(chatContact, "分组 $groupName 不存在")
            return
        }

        val groups = group.contacts.mapNotNull(::groupLabelFromSubject)
        val subscriptions = group.contacts.flatMap { contact ->
            val userSubscriptions = BiliData.dynamic
                .filter { containsEquivalentSubject(it.value.contacts, contact) }
                .map { "${it.value.name} (UID: ${it.key})" }
            val bangumiSubscriptions = BiliData.bangumi
                .filter { containsEquivalentSubject(it.value.contacts, contact) }
                .map { "${it.value.title} (ss${it.value.seasonId})" }
            userSubscriptions + bangumiSubscriptions
        }.distinct()

        val subscriptionText = if (subscriptions.isEmpty()) "订阅列表: 无" else "订阅列表:\n${subscriptions.joinToString("\n")}"
        val msg = if (groups.isEmpty()) {
            "分组: $groupName\n创建者: ${group.creatorContact.ifBlank { group.creator.toString() }}\n当前没有任何群\n$subscriptionText"
        } else {
            "分组: $groupName\n创建者: ${group.creatorContact.ifBlank { group.creator.toString() }}\n包含群:\n${groups.joinToString("\n")}\n$subscriptionText"
        }
        sendText(chatContact, msg)
    }

    private suspend fun subscribe(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 4) {
            sendText(chatContact, "用法: /bili group subscribe <分组名> <UID|ss|md|ep>")
            return
        }
        val groupName = args[2]
        val id = args[3]
        val group = BiliData.group[groupName]
        if (group == null) {
            sendText(chatContact, "分组 $groupName 不存在")
            return
        }
        if (group.contacts.isEmpty()) {
            sendText(chatContact, "分组 $groupName 中没有任何联系人")
            return
        }

        if (!pgcRegex.matches(id)) {
            val uid = id.toLongOrNull()
            if (uid == null) {
                sendText(chatContact, "UID 格式错误")
                return
            }
            val result = DynamicService.addGroupSubscribe(uid, groupName)
            BiliConfigManager.saveData()
            sendText(chatContact, result)
            return
        }

        var addedCount = 0
        var firstError: String? = null
        for (contact in group.contacts) {
            try {
                val result = PgcService.followPgc(id, contact)
                if (!result.contains("失败") && !result.contains("错误")) {
                    addedCount++
                } else if (firstError == null) {
                    firstError = result
                }
            } catch (e: Exception) {
                if (firstError == null) firstError = e.message
            }
        }
        BiliConfigManager.saveData()

        val msg = if (firstError != null && addedCount == 0) {
            "订阅失败：$firstError"
        } else {
            buildString {
                appendLine("订阅操作完成")
                appendLine("目标: $id")
                appendLine("分组: $groupName")
                appendLine("成功添加: $addedCount 个联系人")
                if (firstError != null) appendLine("部分失败: $firstError")
            }.trim()
        }
        sendText(chatContact, msg)
    }

    private suspend fun unsubscribe(chatContact: PlatformContact, args: List<String>) {
        if (args.size < 4) {
            sendText(chatContact, "用法: /bili group unsubscribe <分组名> <UID|ss|md|ep>")
            return
        }
        val groupName = args[2]
        val id = args[3]
        val group = BiliData.group[groupName]
        if (group == null) {
            sendText(chatContact, "分组 $groupName 不存在")
            return
        }
        if (group.contacts.isEmpty()) {
            sendText(chatContact, "分组 $groupName 中没有任何联系人")
            return
        }

        if (!pgcRegex.matches(id)) {
            val uid = id.toLongOrNull()
            if (uid == null) {
                sendText(chatContact, "UID 格式错误")
                return
            }
            val result = DynamicService.removeGroupSubscribe(uid, groupName)
            BiliConfigManager.saveData()
            sendText(chatContact, result)
            return
        }

        var removedCount = 0
        var firstError: String? = null
        for (contact in group.contacts) {
            try {
                val result = PgcService.delPgc(id, contact)
                if (result.contains("成功")) {
                    removedCount++
                } else if (firstError == null && result.contains("失败")) {
                    firstError = result
                }
            } catch (e: Exception) {
                if (firstError == null) firstError = e.message
            }
        }
        BiliConfigManager.saveData()

        val msg = if (firstError != null && removedCount == 0) {
            "取消订阅失败：$firstError"
        } else {
            buildString {
                appendLine("取消订阅操作完成")
                appendLine("目标: $id")
                appendLine("分组: $groupName")
                appendLine("成功移除: $removedCount 个联系人")
                if (firstError != null) appendLine("部分失败: $firstError")
            }.trim()
        }
        sendText(chatContact, msg)
    }
}
