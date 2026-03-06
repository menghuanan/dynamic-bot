package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getEpisodeInfo
import top.bilibili.core.BiliBiliBot
import top.bilibili.napcat.MessageSegment
import top.bilibili.utils.biliClient

object SubscriptionCommandService {
    suspend fun handleAdd(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!isGroup && !CommandPermission.isSuperAdmin(userId)) return
        if (args.size < 2) {
            send(contactId, isGroup, "用法: /bili add <UID|ss|md|ep> [群号]")
            return
        }

        val id = args[1]
        val targetContact = resolveTargetContact(contactId, userId, args, isGroup, "add") ?: return
        val result = if (pgcRegex.matches(id)) {
            PgcService.followPgc(id, targetContact)
        } else {
            val uid = id.toLongOrNull() ?: run {
                send(contactId, isGroup, "UID 格式错误")
                return
            }
            DynamicService.addDirectSubscribe(uid, targetContact, isSelf = false)
        }

        BiliConfigManager.saveData()
        send(contactId, isGroup, result)
    }

    suspend fun handleRemove(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (!isGroup && !CommandPermission.isSuperAdmin(userId)) return
        if (args.size < 2) {
            send(contactId, isGroup, "用法: /bili remove <UID|ss|md|ep> [群号]")
            return
        }

        val id = args[1]
        val targetContact = resolveTargetContact(contactId, userId, args, isGroup, "remove") ?: return
        val result = if (pgcRegex.matches(id)) {
            PgcService.delPgc(id, targetContact)
        } else {
            val uid = id.toLongOrNull() ?: run {
                send(contactId, isGroup, "UID 格式错误")
                return
            }
            DynamicService.removeDirectSubscribe(uid, targetContact, isSelf = false)
        }

        BiliConfigManager.saveData()
        send(contactId, isGroup, result)
    }

    suspend fun handleList(contactId: Long, userId: Long, args: List<String>, isGroup: Boolean) {
        if (args.size == 1) {
            val contact = if (isGroup) "group:$contactId" else "private:$contactId"
            val userSubs = BiliData.dynamic
                .filter { contact in it.value.contacts }
                .map { "${it.value.name} (UID: ${it.key})" }
            val bangumiSubs = BiliData.bangumi
                .filter { contact in it.value.contacts }
                .map { "${it.value.title} (ss${it.value.seasonId})" }
            val subs = userSubs + bangumiSubs
            val msg = if (subs.isEmpty()) "当前群没有任何订阅" else "订阅列表:\n${subs.joinToString("\n")}" 
            send(contactId, isGroup, msg)
            return
        }

        if (!CommandPermission.isSuperAdmin(userId)) {
            send(contactId, isGroup, "权限不足: 仅超级管理员可查看推送范围")
            return
        }

        val id = args[1]
        if (pgcRegex.matches(id)) {
            val match = pgcRegex.find(id)!!
            var idType = match.groupValues[1]
            var idValue = match.groupValues[2].toLong()
            if (idType == "ep") {
                val season = biliClient.getEpisodeInfo(idValue)
                if (season == null) {
                    send(contactId, isGroup, "获取番剧信息失败")
                    return
                }
                idType = "ss"
                idValue = season.seasonId
            }

            val bangumi = when (idType) {
                "ss" -> BiliData.bangumi[idValue]
                "md" -> BiliData.bangumi.values.firstOrNull { it.mediaId == idValue }
                else -> null
            }

            val msg = when (idType) {
                "ss", "md" -> {
                    if (bangumi == null) {
                        "没有订阅过番剧 ${idType}${idValue}"
                    } else {
                        val groups = bangumi.contacts.filter { it.startsWith("group:") }.map { it.removePrefix("group:") }
                        if (groups.isEmpty()) {
                            "${bangumi.title} (ss${bangumi.seasonId})\n没有推送到任何群"
                        } else {
                            "${bangumi.title} (ss${bangumi.seasonId})\n推送到以下群:\n${groups.joinToString("\n")}"
                        }
                    }
                }
                else -> "ID 格式错误"
            }
            send(contactId, isGroup, msg)
            return
        }

        val uid = id.toLongOrNull()
        if (uid == null) {
            send(contactId, isGroup, "UID 或番剧ID 格式错误")
            return
        }

        val subData = BiliData.dynamic[uid]
        if (subData == null) {
            send(contactId, isGroup, "没有订阅过 UID: $uid")
            return
        }

        val groups = subData.contacts.filter { it.startsWith("group:") }.map { it.removePrefix("group:") }
        val msg = if (groups.isEmpty()) {
            "${subData.name} (UID: $uid)\n没有推送到任何群"
        } else {
            "${subData.name} (UID: $uid)\n推送到以下群:\n${groups.joinToString("\n")}"
        }
        send(contactId, isGroup, msg)
    }

    private suspend fun resolveTargetContact(
        contactId: Long,
        userId: Long,
        args: List<String>,
        isGroup: Boolean,
        action: String
    ): String? {
        if (args.size == 2) {
            return if (CommandPermission.isSuperAdmin(userId) || (isGroup && CommandPermission.isGroupAdmin(contactId, userId))) {
                if (isGroup) "group:$contactId" else "private:$contactId"
            } else null
        }

        if (!CommandPermission.isSuperAdmin(userId)) {
            if (isGroup && CommandPermission.isGroupAdmin(contactId, userId)) {
                send(contactId, isGroup, "普通管理员请使用简短格式: /bili $action <UID>（无需群号）")
            }
            return null
        }

        val target = args[2].toLongOrNull()
        if (target == null) {
            send(contactId, isGroup, "群号格式错误")
            return null
        }

        if (action == "add") {
            val available = runCatching {
                if (!BiliBiliBot.isNapCatInitialized()) {
                    false
                } else {
                    BiliBiliBot.napCat.isBotInGroup(target)
                }
            }.getOrDefault(false)

            if (!available) {
                send(contactId, isGroup, "拒绝添加：Bot 不在目标群 $target")
                return null
            }
        }

        return "group:$target"
    }

    private suspend fun send(contactId: Long, isGroup: Boolean, msg: String) {
        if (isGroup) MessageGatewayProvider.require().sendGroupMessage(contactId, listOf(MessageSegment.text(msg)))
        else MessageGatewayProvider.require().sendPrivateMessage(contactId, listOf(MessageSegment.text(msg)))
    }
}
