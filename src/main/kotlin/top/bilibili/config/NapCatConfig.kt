package top.bilibili.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * NapCat 连接配置
 */
@Serializable
data class NapCatConfig(
    /** WebSocket 服务器地址 */
    val host: String = "127.0.0.1",

    /** WebSocket 服务器端口 */
    val port: Int = 3001,

    /** 访问令牌（可选） */
    val token: String = "",

    /** 心跳间隔（毫秒） */
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long = 30000,

    /** 重连间隔（毫秒） */
    @SerialName("reconnect_interval")
    val reconnectInterval: Long = 5000,

    /** 消息格式（固定为 array） */
    @SerialName("message_format")
    val messageFormat: String = "array",

    /** 最大重连次数（-1 为无限） */
    @SerialName("max_reconnect_attempts")
    val maxReconnectAttempts: Int = -1,

    /** 连接超时时间（毫秒） */
    @SerialName("connect_timeout")
    val connectTimeout: Long = 10000
) {
    /** 获取完整的 WebSocket URL */
    fun getWebSocketUrl(): String {
        return "ws://$host:$port"
    }

    /** 检查配置是否有效 */
    fun validate(): Boolean {
        return host.isNotBlank() && port in 1..65535
    }
}

/**
 * 目标配置（QQ 群或私聊）
 */
@Serializable
data class TargetConfig(
    /** 目标类型：group 或 private */
    val type: String,

    /** 目标 ID（群号或 QQ 号） */
    val id: Long,

    /** 是否启用链接解析 */
    @SerialName("enable_link_parse")
    val enableLinkParse: Boolean = true
) {
    companion object {
        fun group(groupId: Long, enableLinkParse: Boolean = true) =
            TargetConfig("group", groupId, enableLinkParse)

        fun private(userId: Long, enableLinkParse: Boolean = true) =
            TargetConfig("private", userId, enableLinkParse)
    }
}

/**
 * 分群管理员配置
 */
@Serializable
data class GroupAdminConfig(
    /** 群号 */
    val groupId: Long,
    
    /** 该群的管理员 QQ 号列表 */
    val userIds: MutableList<Long> = mutableListOf()
)

/**
 * Bot 主配置
 */
@Serializable
data class BotConfig(
    /** NapCat 连接配置 */
    val napcat: NapCatConfig = NapCatConfig(),

    /** 推送目标列表 */
    val targets: MutableList<TargetConfig> = mutableListOf(),

    /** 分群管理员配置列表 */
    val admins: MutableList<GroupAdminConfig> = mutableListOf(),

    /** 首次运行标志 (0: 首次运行, 1: 非首次) */
    @SerialName("first_run_flag")
    var firstRunFlag: Int = 0
)
