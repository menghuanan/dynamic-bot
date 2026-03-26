package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
import top.bilibili.api.getEpisodeInfo
import top.bilibili.connector.PlatformCapabilityService
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.biliClient
import top.bilibili.utils.containsEquivalentSubject
import top.bilibili.utils.groupLabelFromSubject
import top.bilibili.utils.parseCommandPlatformContact
import top.bilibili.utils.toSubject

object SubscriptionCommandService {
    suspend fun handleAdd(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        val isGroup = chatContact.type == PlatformChatType.GROUP
        if (!isGroup && !CommandPermission.isSuperAdmin(senderContact)) return
        if (args.size < 2) {
            sendText(chatContact, "用法: /bili add <UID|ss|md|ep> [群号]")
            return
        }

        val id = args[1]
        val targetContact = resolveTargetContact(chatContact, senderContact, args, "add") ?: return
        val result = if (pgcRegex.matches(id)) {
            PgcService.followPgc(id, targetContact.toSubject())
        } else {
            val uid = id.toLongOrNull() ?: run {
                sendText(chatContact, "UID 格式错误")
                return
            }
            DynamicService.addDirectSubscribe(uid, targetContact.toSubject(), isSelf = false)
        }

        BiliConfigManager.saveData()
        sendText(chatContact, result)
    }

    suspend fun handleRemove(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        val isGroup = chatContact.type == PlatformChatType.GROUP
        if (!isGroup && !CommandPermission.isSuperAdmin(senderContact)) return
        if (args.size < 2) {
            sendText(chatContact, "用法: /bili remove <UID|ss|md|ep> [群号]")
            return
        }

        val id = args[1]
        val targetContact = resolveTargetContact(chatContact, senderContact, args, "remove") ?: return
        val result = if (pgcRegex.matches(id)) {
            PgcService.delPgc(id, targetContact.toSubject())
        } else {
            val uid = id.toLongOrNull() ?: run {
                sendText(chatContact, "UID 格式错误")
                return
            }
            DynamicService.removeDirectSubscribe(uid, targetContact.toSubject(), isSelf = false)
        }

        BiliConfigManager.saveData()
        sendText(chatContact, result)
    }

    suspend fun handleList(chatContact: PlatformContact, senderContact: PlatformContact, args: List<String>) {
        val isGroup = chatContact.type == PlatformChatType.GROUP
        if (args.size == 1) {
            val contact = chatContact.toSubject()
            val userSubs = BiliData.dynamic
                .filter { containsEquivalentSubject(it.value.contacts, contact) }
                .map { "${it.value.name} (UID: ${it.key})" }
            val bangumiSubs = BiliData.bangumi
                .filter { containsEquivalentSubject(it.value.contacts, contact) }
                .map { "${it.value.title} (ss${it.value.seasonId})" }
            val subs = userSubs + bangumiSubs
            val msg = if (subs.isEmpty()) "当前会话没有任何订阅" else "订阅列表:\n${subs.joinToString("\n")}"
            sendText(chatContact, msg)
            return
        }

        if (!CommandPermission.isSuperAdmin(senderContact)) {
            sendText(chatContact, "权限不足: 仅超级管理员可查看推送范围")
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
                    sendText(chatContact, "获取番剧信息失败")
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
                        val groups = bangumi.contacts.mapNotNull(::groupLabelFromSubject)
                        if (groups.isEmpty()) {
                            "${bangumi.title} (ss${bangumi.seasonId})\n没有推送到任何群"
                        } else {
                            "${bangumi.title} (ss${bangumi.seasonId})\n推送到以下群:\n${groups.joinToString("\n")}"
                        }
                    }
                }
                else -> "ID 格式错误"
            }
            sendText(chatContact, msg)
            return
        }

        val uid = id.toLongOrNull()
        if (uid == null) {
            sendText(chatContact, "UID 或番剧ID 格式错误")
            return
        }

        val subData = BiliData.dynamic[uid]
        if (subData == null) {
            sendText(chatContact, "没有订阅过 UID: $uid")
            return
        }

        val groups = subData.contacts.mapNotNull(::groupLabelFromSubject)
        val msg = if (groups.isEmpty()) {
            "${subData.name} (UID: $uid)\n没有推送到任何群"
        } else {
            "${subData.name} (UID: $uid)\n推送到以下群:\n${groups.joinToString("\n")}"
        }
        sendText(chatContact, msg)
    }

    private suspend fun resolveTargetContact(
        chatContact: PlatformContact,
        senderContact: PlatformContact,
        args: List<String>,
        action: String,
    ): PlatformContact? {
        val isGroup = chatContact.type == PlatformChatType.GROUP
        if (args.size == 2) {
            return if (CommandPermission.isSuperAdmin(senderContact) || (isGroup && CommandPermission.isGroupAdmin(chatContact, senderContact))) {
                chatContact
            } else {
                null
            }
        }

        if (!CommandPermission.isSuperAdmin(senderContact)) {
            if (isGroup && CommandPermission.isGroupAdmin(chatContact, senderContact)) {
                sendText(chatContact, "普通管理员请使用简短格式: /bili $action <UID>（无需群号）")
            }
            return null
        }

        val target = parseCommandPlatformContact(
            raw = args[2],
            defaultPlatform = chatContact.platform,
            defaultType = PlatformChatType.GROUP,
        )
        if (target == null) {
            sendText(chatContact, "群号格式错误")
            return null
        }

        if (action == "add") {
            val available = runCatching {
                PlatformCapabilityService.canSendMessageTo(target)
            }.getOrDefault(false)

            if (!available) {
                sendText(chatContact, "拒绝添加：Bot 不在目标群 ${target.id}")
                return null
            }
        }

        return target
    }
}
