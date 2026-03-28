package top.bilibili

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import top.bilibili.service.TriggerMode
import top.bilibili.utils.normalizeContactSubject
import top.bilibili.utils.CacheType

/**
 * 插件运行期的主配置模型。
 */
@Serializable
data class BiliConfig(
    // 管理员
    val admin: Long = 0L,
    @SerialName("admin_contact")
    val adminContact: String = "",

    // 功能开关
    val enableConfig: EnableConfig = EnableConfig(),

    // 账号配置
    val accountConfig: BiliAccountConfig = BiliAccountConfig(),

    // 检测配置
    val checkConfig: CheckConfig = CheckConfig(),

    // 推送配置
    val pushConfig: PushConfig = PushConfig(),

    // 图片配置
    val imageConfig: ImageConfig = ImageConfig(),

    // 模板配置
    val templateConfig: TemplateConfig = TemplateConfig(),

    // 缓存配置
    val cacheConfig: CacheConfig = CacheConfig(),

    // 代理配置
    val proxyConfig: ProxyConfig = ProxyConfig(),

    // 翻译配置
    val translateConfig: TranslateConfig = TranslateConfig(),

    // 链接解析配置
    val linkResolveConfig: LinkResolveConfig = LinkResolveConfig(),
) {
    /**
     * 统一返回超级管理员联系人，优先使用显式 subject，再回退到旧 OneBot11 数字 QQ 号。
     */
    fun normalizedAdminSubject(): String? {
        if (adminContact.isNotBlank()) {
            return normalizeContactSubject(adminContact) ?: adminContact
        }
        return if (admin > 0L) "onebot11:private:$admin" else null
    }
}

/**
 * 功能开关配置。
 */
@Serializable
data class EnableConfig(
    val debugMode: Boolean = false,
    val drawEnable: Boolean = true,
    val pushDrawEnable: Boolean = true,
    val notifyEnable: Boolean = true,
    val liveCloseNotifyEnable: Boolean = true,
    val lowSpeedEnable: Boolean = true,
    var translateEnable: Boolean = false,
    val proxyEnable: Boolean = false,
    val cacheClearEnable: Boolean = true,
)

/**
 * 翻译相关配置。
 */
@Serializable
data class TranslateConfig(
    val cutLine: String = "\n\n〓〓〓 翻译 〓〓〓\n",
    var baidu: BaiduTranslateConfig = BaiduTranslateConfig()
) {
    @Serializable
    data class BaiduTranslateConfig(
        var APP_ID: String = "",
        var SECURITY_KEY: String = "",
    )
}

/**
 * 图片渲染与配色配置。
 */
@Serializable
data class ImageConfig(
    val quality: String = "1000w",
    val theme: String = "v3",
    var font: String = "",
    var defaultColor: String = "#d3edfa",
    var cardOrnament: String = "FanCard",
    val colorGenerator: ColorGenerator = ColorGenerator(),
    val badgeEnable: BadgeEnable = BadgeEnable(),
) {

    @Serializable
    data class ColorGenerator(
        val hueStep: Int = 30,
        val lockSB: Boolean = true,
        val saturation: Float = 0.25f,
        val brightness: Float = 1f,
    )

    @Serializable
    data class BadgeEnable(
        var left: Boolean = true,
        var right: Boolean = false,
    ) {
        val enable: Boolean get() = left || right
    }
}

/**
 * 代理相关配置。
 */
@Serializable
data class ProxyConfig(
    val proxy: List<String> = listOf(),
//    val verifyUrl: String = "http://httpbin.org",
)

/**
 * B 站账号与自动关注分组配置。
 */
@Serializable
data class BiliAccountConfig(
    var cookie: String = "",
    var autoFollow: Boolean = true,
    var followGroup: String = "Bot关注"
)

/**
 * 动态与直播检测频率配置。
 */
@Serializable
data class CheckConfig(
    var interval: Int = 15,
    var liveInterval: Int = 15,
    /** 低频检测时段，格式："开始小时-结束小时"，如 "22-8" 表示晚上10点到早上8点（跨午夜），默认晚22点到早8点为低频时段 */
    var lowSpeedTime: String = "22-8",
    /** 低频检测间隔范围（秒），格式："最小-最大"，如 "60-240" 表示60-240秒随机间隔，默认为正常间隔的2倍 */
    var lowSpeedRange: String = "60-240",
    /** 正常检测间隔范围（秒），格式："最小-最大"，如 "30-120" 表示30-120秒随机间隔 */
    var normalRange: String = "30-120",
    val checkReportInterval: Int = 10,
    val timeout: Int = 10
)


/**
 * 推送节流相关配置。
 */
@Serializable
data class PushConfig(
    val messageInterval: Long = 100,
    val pushInterval: Long = 500,
    val toShortLink: Boolean = false,
)

/**
 * 文本模板配置。
 */
@Serializable
data class TemplateConfig(
    var defaultDynamicPush: String = "OneMsg",
    var defaultLivePush: String = "OneMsg",
    var defaultLiveClose: String = "SimpleMsg",
    val dynamicPush: MutableMap<String, String> = mutableMapOf(
        "DrawOnly" to "{draw}",
        "TextOnly" to "{name}@{type}\n{link}\n{content}\n{images}",
        "OneMsg" to "{draw}\n{name}@{type}\n{link}",
        "TwoMsg" to "{draw}\r{name}@{uid}@{type}\n{time}\n{link}",
    ),
    val livePush: MutableMap<String, String> = mutableMapOf(
        "DrawOnly" to "{draw}",
        "TextOnly" to "{name}@直播\n{link}\n标题: {title}",
        "OneMsg" to "{draw}\n{name}@直播\n{link}",
        "TwoMsg" to "{draw}\r{name}@{uid}@直播\n{title}\n{time}\n{link}",
    ),
    val liveClose: MutableMap<String, String> = mutableMapOf(
        "SimpleMsg" to "{name} 直播结束啦!\n直播时长: {duration}",
        "ComplexMsg" to "{name} 直播结束啦!\n标题: {title}\n直播时长: {duration}"
    ),
    var footer: FooterConfig = FooterConfig(),
)

/**
 * 页脚文案配置。
 */
@Serializable
data class FooterConfig(
    var dynamicFooter: String = "",
    var liveFooter: String = "",
    var footerAlign: String = "LEFT"
)

/**
 * 本地缓存行为配置。
 */
@Serializable
data class CacheConfig(
    val downloadOriginal: Boolean = true,
    val expires: Map<CacheType, Int> = mapOf(
        CacheType.DRAW to 7,
        CacheType.IMAGES to 7,
        CacheType.EMOJI to 7,
        CacheType.USER to 7,
        CacheType.OTHER to 7,
    )
)

/**
 * 链接解析触发方式配置。
 */
@Serializable
data class LinkResolveConfig(
    val triggerMode: TriggerMode = TriggerMode.At,
    val drawEnable: Boolean = true,
    val returnLink: Boolean = false,
)

// (www\.bilibili\.com/video/((BV[0-9A-z]{10})|(av\d{1,10})))|^(BV[0-9A-z]{10})|^(av\d{1,10})
// (www\.bilibili\.com/read/cv\d{1,10})|^(cv\d{1,10})
// ((www|m)\.bilibili\.com/bangumi/(play|media)/(ss|ep|md)\d+)|^((ss|ep|md)\d+)
// [tm]\.bilibili\.com/(dynamic/)?\d+
// live\.bilibili\.com/(h5/)?\d+
// space\.bilibili\.com/\d+
// (b23\.tv|bili2233\.cn)\\?/[0-9A-z]+
