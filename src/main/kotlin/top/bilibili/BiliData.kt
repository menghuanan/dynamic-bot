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
    var dynamic: MutableMap<Long, SubData> = mutableMapOf(0L to SubData("ALL"))

    // key: contact - 动态过滤
    var filter: MutableMap<String, MutableMap<Long, DynamicFilter>> = mutableMapOf()

    // key: template name - 动态推送模板
    var dynamicPushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // key: template name - 直播推送模板
    var livePushTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // key: template name - 直播结束模板
    var liveCloseTemplate: MutableMap<String, MutableSet<String>> = mutableMapOf()

    // key: group name - 分组
    var group: MutableMap<String, Group> = mutableMapOf()

    // key: season id - 番剧
    var bangumi: MutableMap<Long, Bangumi> = mutableMapOf()
}

@Serializable
data class SubData(
    val name: String,
    var color: String? = null,
    var last: Long = 0L,
    var lastLive: Long = 0L,
    val contacts: MutableSet<String> = mutableSetOf(),
    val banList: MutableMap<String, String> = mutableMapOf(),
)

@Serializable
data class Group(
    val name: String,
    val creator: Long,
    val admin: MutableSet<Long> = mutableSetOf(),
    val contacts: MutableSet<String> = mutableSetOf(),
) {
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

@Serializable
enum class FilterType {
    TYPE,
    REGULAR
}

@Serializable
data class DynamicFilter(
    val typeSelect: TypeFilter = TypeFilter(),
    val regularSelect: RegularFilter = RegularFilter(),
)

@Serializable
data class TypeFilter(
    var mode: FilterMode = FilterMode.BLACK_LIST,
    val list: MutableList<DynamicFilterType> = mutableListOf()
)

@Serializable
data class RegularFilter(
    var mode: FilterMode = FilterMode.BLACK_LIST,
    val list: MutableList<String> = mutableListOf()
)

@Serializable
enum class FilterMode(val value: String) {
    WHITE_LIST("白名单"),
    BLACK_LIST("黑名单")
}

@Serializable
enum class DynamicFilterType(val value: String) {
    DYNAMIC("动态"),
    FORWARD("转发动态"),
    VIDEO("视频"),
    MUSIC("音乐"),
    ARTICLE("专栏"),
    LIVE("直播"),
}