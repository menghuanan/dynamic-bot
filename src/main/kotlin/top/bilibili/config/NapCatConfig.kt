package top.bilibili.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import top.bilibili.connector.PlatformType

@Serializable
data class NapCatConfig(
    val host: String = "127.0.0.1",
    val port: Int = 3001,
    val token: String = "",
    @SerialName("use_tls")
    val useTls: Boolean = false,
    @SerialName("heartbeat_interval")
    val heartbeatInterval: Long = 30000,
    @SerialName("reconnect_interval")
    val reconnectInterval: Long = 5000,
    @SerialName("message_format")
    val messageFormat: String = "array",
    @SerialName("send_mode")
    val sendMode: String = "base64",
    @SerialName("max_reconnect_attempts")
    val maxReconnectAttempts: Int = -1,
    @SerialName("connect_timeout")
    val connectTimeout: Long = 10000,
) {
    fun getWebSocketUrl(): String {
        val protocol = if (useTls) "wss" else "ws"
        return "$protocol://$host:$port"
    }

    fun validate(): Boolean {
        val normalizedSendMode = sendMode.lowercase()
        return host.isNotBlank() &&
            port in 1..65535 &&
            normalizedSendMode in setOf("file", "base64")
    }
}

@Serializable
data class TargetConfig(
    val type: String,
    val id: Long,
    // 尚未启用:
    // @SerialName("enable_link_parse")
    // val enableLinkParse: Boolean = true
) {
    companion object {
        fun group(groupId: Long) = TargetConfig("group", groupId)

        fun private(userId: Long) = TargetConfig("private", userId)
    }
}

@Serializable
data class GroupAdminConfig(
    val groupId: Long,
    val userIds: MutableList<Long> = mutableListOf(),
)

@Serializable
data class QQOfficialConfig(
    @SerialName("app_id")
    val appId: String = "",
    @SerialName("app_secret")
    val appSecret: String = "",
    @SerialName("bot_token")
    val botToken: String = "",
)

@Serializable
data class PlatformConfig(
    val type: PlatformType = PlatformType.ONEBOT11,
    val onebot11: NapCatConfig = NapCatConfig(),
    @SerialName("qq_official")
    val qqOfficial: QQOfficialConfig = QQOfficialConfig(),
)

@Serializable
data class BotConfig(
    val platform: PlatformConfig = PlatformConfig(),
    // Legacy OneBot11 config. Retained for backward-compatible config loading.
    val napcat: NapCatConfig = NapCatConfig(),
    val targets: MutableList<TargetConfig> = mutableListOf(),
    val admins: MutableList<GroupAdminConfig> = mutableListOf(),
    @SerialName("first_run_flag")
    var firstRunFlag: Int = 0,
) {
    fun selectedPlatformType(): PlatformType = platform.type

    fun selectedOneBot11Config(): NapCatConfig {
        return if (platform.onebot11 == NapCatConfig() && napcat != NapCatConfig()) {
            napcat
        } else {
            platform.onebot11
        }
    }

    fun validateSelectedPlatform(): Boolean {
        return when (selectedPlatformType()) {
            PlatformType.ONEBOT11 -> selectedOneBot11Config().validate()
            PlatformType.QQ_OFFICIAL -> true
        }
    }
}
