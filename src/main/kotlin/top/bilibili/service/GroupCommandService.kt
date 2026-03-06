package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.Group
import top.bilibili.core.BiliBiliBot
import top.bilibili.napcat.MessageSegment

object GroupCommandService {
    suspend fun handle(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId)) return

        if (args.size < 2) {
            send(contactId, isGroup, "用法: /bili group <create|delete|add|remove|list|subscribe|unsubscribe>")
            return
        }

        when (args[1].lowercase()) {
            "create" -> create(contactId, userId, args, isGroup)
            "delete", "del" -> delete(contactId, args, isGroup)
            "add" -> add(contactId, args, isGroup)
            "remove", "rm" -> remove(contactId, args, isGroup)
            "list", "ls" -> list(contactId, userId, args, isGroup)
            "subscribe", "sub" -> subscribe(contactId, args, isGroup)
            "unsubscribe", "unsub" -> unsubscribe(contactId, args, isGroup)
            else -> send(contactId, isGroup, "未知子命令: ${args[1]}")
        }
    }

    suspend fun listGroups(contactId: Long, userId: Long, isGroup: Boolean) {
        if (!CommandPermission.isSuperAdmin(userId)) return
        val groups = BiliData.group
        val msg = if (groups.isEmpty()) {
            "当前没有任何分组"
        } else {
            val groupList = groups.map { (name, group) ->
                "$name (${group.contacts.size} 个群)"
            }.joinToString("\n")
            "分组列表:\n$groupList\n\n使用 /bili group list <分组名> 查看详情"
        }
        send(contactId, isGroup, msg)
    }

    private suspend fun create(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            send(contactId, isGroup, "用法: /bili group create <分组名>")
            return
        }

        val groupName = args[2]
        if (groupName in BiliData.group) {
            send(contactId, isGroup, "分组 $groupName 已存在")
            return
        }

        BiliData.group[groupName] = Group(
            name = groupName,
            creator = userId,
            admin = mutableSetOf(userId),
            contacts = mutableSetOf()
        )
        BiliConfigManager.saveData()
        send(contactId, isGroup, "成功创建分组: $groupName")
    }

    private suspend fun delete(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            send(contactId, isGroup, "用法: /bili group delete <分组名>")
            return
        }
        val groupName = args[2]
        if (groupName !in BiliData.group) {
            send(contactId, isGroup, "分组 $groupName 不存在")
            return
        }

        DynamicService.deleteGroupRef(groupName)
        BiliData.group.remove(groupName)
        BiliConfigManager.saveData()
        send(contactId, isGroup, "成功删除分组: $groupName")
    }

    private suspend fun add(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            send(contactId, isGroup, "用法: /bili group add <分组名> <群号>")
            return
        }
        val groupName = args[2]
        val targetGroupId = args[3].toLongOrNull()
        if (targetGroupId == null) {
            send(contactId, isGroup, "群号格式错误")
            return
        }
        val group = BiliData.group[groupName]
        if (group == null) {
            send(contactId, isGroup, "分组 $groupName 不存在")
            return
        }

        val inGroup = runCatching {
            if (!BiliBiliBot.isNapCatInitialized()) false else BiliBiliBot.napCat.isBotInGroup(targetGroupId)
        }.getOrDefault(false)
        if (!inGroup) {
            send(contactId, isGroup, "拒绝添加：Bot 不在目标群 $targetGroupId")
            return
        }

        val contactStr = "group:$targetGroupId"
        if (contactStr in group.contacts) {
            send(contactId, isGroup, "群 $targetGroupId 已在分组 $groupName 中")
            return
        }
        group.contacts.add(contactStr)
        DynamicService.refreshGroupRef(groupName)
        BiliConfigManager.saveData()
        send(contactId, isGroup, "成功将群 $targetGroupId 加入分组 $groupName")
    }

    private suspend fun remove(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            send(contactId, isGroup, "用法: /bili group remove <分组名> <群号>")
            return
        }
        val groupName = args[2]
        val targetGroupId = args[3].toLongOrNull()
        if (targetGroupId == null) {
            send(contactId, isGroup, "群号格式错误")
            return
        }
        val group = BiliData.group[groupName]
        if (group == null) {
            send(contactId, isGroup, "分组 $groupName 不存在")
            return
        }
        val contactStr = "group:$targetGroupId"
        if (contactStr !in group.contacts) {
            send(contactId, isGroup, "群 $targetGroupId 不在分组 $groupName 中")
            return
        }
        group.contacts.remove(contactStr)
        DynamicService.refreshGroupRef(groupName)
        BiliConfigManager.saveData()
        send(contactId, isGroup, "成功从分组 $groupName 移除群 $targetGroupId")
    }

    private suspend fun list(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 3) {
            listGroups(contactId, userId, isGroup)
            return
        }
        val groupName = args[2]
        val group = BiliData.group[groupName]
        if (group == null) {
            send(contactId, isGroup, "分组 $groupName 不存在")
            return
        }

        val groups = group.contacts.filter { it.startsWith("group:") }.map { it.removePrefix("group:") }
        val subscriptions = group.contacts.flatMap { contact ->
            val userSubscriptions = BiliData.dynamic
                .filter { contact in it.value.contacts }
                .map { "${it.value.name} (UID: ${it.key})" }
            val bangumiSubscriptions = BiliData.bangumi
                .filter { contact in it.value.contacts }
                .map { "${it.value.title} (ss${it.value.seasonId})" }
            userSubscriptions + bangumiSubscriptions
        }.distinct()

        val subscriptionText = if (subscriptions.isEmpty()) "订阅列表: 无" else "订阅列表:\n${subscriptions.joinToString("\n")}"
        val msg = if (groups.isEmpty()) {
            "分组: $groupName\n创建者: ${group.creator}\n当前没有任何群\n$subscriptionText"
        } else {
            "分组: $groupName\n创建者: ${group.creator}\n包含群:\n${groups.joinToString("\n")}\n$subscriptionText"
        }
        send(contactId, isGroup, msg)
    }

    private suspend fun subscribe(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            send(contactId, isGroup, "用法: /bili group subscribe <分组名> <UID|ss|md|ep>")
            return
        }
        val groupName = args[2]
        val id = args[3]
        val group = BiliData.group[groupName]
        if (group == null) {
            send(contactId, isGroup, "分组 $groupName 不存在")
            return
        }
        if (group.contacts.isEmpty()) {
            send(contactId, isGroup, "分组 $groupName 中没有任何联系人")
            return
        }

        if (!pgcRegex.matches(id)) {
            val uid = id.toLongOrNull()
            if (uid == null) {
                send(contactId, isGroup, "UID 格式错误")
                return
            }
            val result = DynamicService.addGroupSubscribe(uid, groupName)
            BiliConfigManager.saveData()
            send(contactId, isGroup, result)
            return
        }

        var addedCount = 0
        var firstError: String? = null
        for (contact in group.contacts) {
            try {
                val result = PgcService.followPgc(id, contact)
                if (!result.contains("订阅过") && (result.contains("成功") || (!result.contains("失败") && !result.contains("错误")))) {
                    addedCount++
                } else if (firstError == null && result.contains("失败")) {
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
        send(contactId, isGroup, msg)
    }

    private suspend fun unsubscribe(contactId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size < 4) {
            send(contactId, isGroup, "用法: /bili group unsubscribe <分组名> <UID|ss|md|ep>")
            return
        }
        val groupName = args[2]
        val id = args[3]
        val group = BiliData.group[groupName]
        if (group == null) {
            send(contactId, isGroup, "分组 $groupName 不存在")
            return
        }
        if (group.contacts.isEmpty()) {
            send(contactId, isGroup, "分组 $groupName 中没有任何联系人")
            return
        }

        if (!pgcRegex.matches(id)) {
            val uid = id.toLongOrNull()
            if (uid == null) {
                send(contactId, isGroup, "UID 格式错误")
                return
            }
            val result = DynamicService.removeGroupSubscribe(uid, groupName)
            BiliConfigManager.saveData()
            send(contactId, isGroup, result)
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
        send(contactId, isGroup, msg)
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, msg: String) {
        if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }
}
