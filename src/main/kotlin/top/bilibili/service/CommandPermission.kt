package top.bilibili.service

import top.bilibili.BiliConfigManager
import top.bilibili.config.ConfigManager
import top.bilibili.connector.PlatformContact
import top.bilibili.utils.subjectsEquivalent
import top.bilibili.utils.toSubject

object CommandPermission {
    /**
     * 使用平台联系人判断超级管理员，兼容旧 OneBot11 数字管理员配置。
     */
    fun isSuperAdmin(user: PlatformContact): Boolean {
        val adminSubject = BiliConfigManager.config.normalizedAdminSubject() ?: return false
        return subjectsEquivalent(user.toSubject(), adminSubject)
    }

    /**
     * 使用平台联系人判断当前会话是否存在群管理员授权，兼容旧数值配置与新 subject 配置。
     */
    fun isGroupAdmin(group: PlatformContact, user: PlatformContact): Boolean {
        val groupSubject = group.toSubject()
        val userSubject = user.toSubject()
        return ConfigManager.botConfig.admins.any { config ->
            subjectsEquivalent(config.normalizedGroupContact(), groupSubject) &&
                config.normalizedUserContacts().any { subjectsEquivalent(it, userSubject) }
        }
    }
}
