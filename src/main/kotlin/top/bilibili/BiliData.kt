package top.bilibili

import kotlinx.serialization.Serializable

/**
 * BiliData 全局单例
 * 注意：不再直接序列化此 object，而是通过 BiliDataWrapper 进行序列化
 * @see BiliDataWrapper
 */
object BiliData {
    // 数据版本
    var dataVersion: Int = 0

    // key: uid - 订阅信息
    var dynamic: MutableMap<Long, SubData> = mutableMapOf()

    // key: contact - 动态过滤
    var filter: MutableMap<String, MutableMap<Long, DynamicFilter>> = mutableMapOf()

    // key: template name - 动态推送模板
    var dynamicPushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // key: template name - 直播推送模板
    var livePushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // key: template name - 直播结束模板
    var liveCloseTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // key: subject -> uid -> template name（按 UID 绑定模板）
    var dynamicPushTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf()
    var livePushTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf()
    var liveCloseTemplateByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf()

    // key: scope -> uid -> policy（固定顺位模板与随机池共用同一份策略）
    var dynamicTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf()
    var liveTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf()
    var liveCloseTemplatePolicyByScope: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf()

    // key: subject -> uid -> custom color
    var dynamicColorByUid: MutableMap<String, MutableMap<Long, String>> = mutableMapOf()

// key: subject -> uid -> at-all types
    var atAll: MutableMap<String, MutableMap<Long, MutableSet<AtAllType>>> = mutableMapOf()

    // key: group name - 分组
    var group: MutableMap<String, Group> = mutableMapOf()

    // key: season id - 番剧
    var bangumi: MutableMap<Long, Bangumi> = mutableMapOf()

    // 链接解析黑名单 - 忽略指定QQ号的链接解析请求
    var linkParseBlacklist: MutableSet<Long> = mutableSetOf()

    // 链接解析黑名单（平台联系人版）- 新增写入走该集合，旧 Long 集合保留兼容读取
    var linkParseBlacklistContacts: MutableSet<String> = mutableSetOf()
}

/**
 * 模板策略模型。
 * 同一份模板列表同时承担固定顺位模板与随机池配置。
 */
@Serializable
data class TemplatePolicy(
    val templates: MutableList<String> = mutableListOf(),
    var randomEnabled: Boolean = false,
)

/**
 * 订阅数据模型。
 */
@Serializable
data class SubData(
    var name: String,
    var last: Long = 0L,
    var lastLive: Long = 0L,
    val contacts: MutableSet<String> = mutableSetOf(),
    // 订阅来源引用：
    // - direct:<contact>  表示 /bili add 建立的直接订阅
    // - groupRef:<name>   表示 /bili group subscribe 建立的分组订阅
    val sourceRefs: MutableSet<String> = mutableSetOf(),
    val banList: MutableMap<String, String> = mutableMapOf(),
)

/**
 * 分组配置模型。
 */
@Serializable
data class Group(
    val name: String,
    val creator: Long,
    val admin: MutableSet<Long> = mutableSetOf(),
    var creatorContact: String = "",
    val adminContacts: MutableSet<String> = mutableSetOf(),
    val contacts: MutableSet<String> = mutableSetOf(),
) {
    /**
     * 以适合命令行和消息展示的格式输出分组摘要。
     */
    override fun toString(): String {
        return """
分组名: $name
创建者: $creator

管理员:
${admin.joinToString("\n").ifEmpty { "暂无管理员" }}

用户:
${contacts.joinToString("\n").ifEmpty { "暂无用户" }}
""".trimIndent()
    }
}

/**
 * 番剧订阅数据模型。
 */
@Serializable
data class Bangumi(
    val title: String,
    val seasonId: Long,
    val mediaId: Long,
    val type: String,
    var isEnd: Boolean = false,
    var color: String? = null,
    val contacts: MutableSet<String> = mutableSetOf(),
)

/**
 * 过滤器类型。
 */
@Serializable
enum class FilterType {
    TYPE,
    REGULAR
}

/**
 * 动态过滤配置。
 */
@Serializable
data class DynamicFilter(
    val typeSelect: TypeFilter = TypeFilter(),
    val regularSelect: RegularFilter = RegularFilter(),
)

/**
 * 按动态类型过滤的配置。
 */
@Serializable
data class TypeFilter(
    var mode: FilterMode = FilterMode.BLACK_LIST,
    val list: MutableList<DynamicFilterType> = mutableListOf()
)

/**
 * 按正则表达式过滤的配置。
 */
@Serializable
data class RegularFilter(
    var mode: FilterMode = FilterMode.BLACK_LIST,
    val list: MutableList<String> = mutableListOf()
)

/**
 * 过滤器工作模式。
 */
@Serializable
enum class FilterMode(val value: String) {
    WHITE_LIST("白名单"),
    BLACK_LIST("黑名单")
}

/**
 * 可参与过滤的动态类型。
 */
@Serializable
enum class DynamicFilterType(val value: String) {
    DYNAMIC("动态"),
    FORWARD("转发动态"),
    VIDEO("视频"),
    MUSIC("音乐"),
    ARTICLE("专栏"),
    LIVE("直播"),
}

/**
 * 可配置的艾特全体策略。
 */
@Serializable
enum class AtAllType(val value: String) {
    ALL("全部"),
    DYNAMIC("全部动态"),
    VIDEO("视频"),
    MUSIC("音乐"),
    ARTICLE("专栏"),
    LIVE("直播"),
}
