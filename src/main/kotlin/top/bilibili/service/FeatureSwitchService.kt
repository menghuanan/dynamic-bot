package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager

object FeatureSwitchService {
    fun canRenderPushDraw(config: BiliConfig = BiliConfigManager.config): Boolean {
        return config.enableConfig.drawEnable && config.enableConfig.pushDrawEnable
    }

    fun canRenderLinkResolveDraw(config: BiliConfig = BiliConfigManager.config): Boolean {
        return config.enableConfig.drawEnable && config.linkResolveConfig.drawEnable
    }

    fun canRenderLoginQr(config: BiliConfig = BiliConfigManager.config): Boolean {
        return true
    }

    /**
     * 管理通知默认不回发给触发通知的管理员联系人，避免自回环。
     */
    fun canSendManagedAdminNotice(config: BiliConfig = BiliConfigManager.config, subject: String? = null): Boolean {
        val adminSubject = config.normalizedAdminSubject()
        return config.enableConfig.notifyEnable && (subject == null || adminSubject == null || subject != adminSubject)
    }
}
