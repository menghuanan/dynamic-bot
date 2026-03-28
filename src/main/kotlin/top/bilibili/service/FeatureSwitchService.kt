package top.bilibili.service

import top.bilibili.BiliConfig
import top.bilibili.BiliConfigManager

/**
 * 集中封装运行期开关判断，避免功能门禁条件散落在业务代码里。
 */
object FeatureSwitchService {
    /**
     * 统一判断推送绘图能力是否开启，避免消息构建链路重复读取配置。
     */
    fun canRenderPushDraw(config: BiliConfig = BiliConfigManager.config): Boolean {
        return config.enableConfig.drawEnable && config.enableConfig.pushDrawEnable
    }

    /**
     * 统一判断链接解析绘图是否开启，保持搜索卡片与配置开关一致。
     */
    fun canRenderLinkResolveDraw(config: BiliConfig = BiliConfigManager.config): Boolean {
        return config.enableConfig.drawEnable && config.linkResolveConfig.drawEnable
    }

    /**
     * 为登录链路保留独立开关入口，便于后续按平台能力收紧二维码发送策略。
     */
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
