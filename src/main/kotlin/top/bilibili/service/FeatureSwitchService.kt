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

    fun canSendManagedAdminNotice(config: BiliConfig = BiliConfigManager.config, subject: Long? = null): Boolean {
        return config.enableConfig.notifyEnable && (subject == null || subject != config.admin)
    }
}
