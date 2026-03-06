package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.config.ConfigManager

object CommandPermission {
    fun isSuperAdmin(userId: Long): Boolean {
        return userId == BiliConfigManager.config.admin
    }

    fun isGroupAdmin(groupId: Long, userId: Long): Boolean {
        val groupConfig = ConfigManager.botConfig.admins.find { it.groupId == groupId }
        return groupConfig?.userIds?.contains(userId) == true
    }
}
