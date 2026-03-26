package top.bilibili.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import top.bilibili.connector.PlatformAdapterKind
import top.bilibili.connector.PlatformType
import top.bilibili.utils.normalizeContactSubject

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
    @SerialName("contact")
    val contact: String = "",
    // 尚未启用:
    // @SerialName("enable_link_parse")
    // val enableLinkParse: Boolean = true
) {
    /**
     * 统一返回目标联系人 subject；若未显式配置则回退到旧 OneBot11 数字字段。
     */
    fun normalizedContact(): String? {
        if (contact.isNotBlank()) {
            return normalizeContactSubject(contact) ?: contact
        }
        return when (type) {
            "group" -> "onebot11:group:$id"
            "private" -> "onebot11:private:$id"
            else -> null
        }
    }

    companion object {
        fun group(groupId: Long) = TargetConfig("group", groupId)

        fun private(userId: Long) = TargetConfig("private", userId)
    }
}

@Serializable
data class GroupAdminConfig(
    val groupId: Long = 0L,
    val userIds: MutableList<Long> = mutableListOf(),
    @SerialName("group_contact")
    val groupContact: String = "",
    @SerialName("user_contacts")
    val userContacts: MutableList<String> = mutableListOf(),
) {
    /**
     * 统一返回管理员配置绑定的群联系人。
     */
    fun normalizedGroupContact(): String? {
        if (groupContact.isNotBlank()) {
            return normalizeContactSubject(groupContact) ?: groupContact
        }
        return if (groupId > 0L) "onebot11:group:$groupId" else null
    }

    /**
     * 统一返回管理员配置绑定的用户联系人集合。
     */
    fun normalizedUserContacts(): Set<String> {
        val normalized = linkedSetOf<String>()
        userContacts.forEach { raw ->
            val subject = normalizeContactSubject(raw) ?: raw
            normalized += subject
        }
        userIds.filter { it > 0L }.forEach { id ->
            normalized += "onebot11:private:$id"
        }
        return normalized
    }
}

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
    @EncodeDefault
    @SerialName("adapter")
    val adapter: String = "onebot11",
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

    /**
     * 统一将配置里的适配器文本归一为受支持的选择：
     * - 缺失时优先回退到 generic onebot11
     * - 仅在明确 legacy napcat 配置存在时推断为 napcat
     * - 未知值统一回退到 generic onebot11
     */
    fun selectedAdapterKind(): PlatformAdapterKind {
        return when (selectedPlatformType()) {
            PlatformType.ONEBOT11 -> {
                normalizeAdapterKind(platform.adapter)
            }
            PlatformType.QQ_OFFICIAL -> PlatformAdapterKind.QQ_OFFICIAL
        }
    }

    fun normalizedPlatformConfig(): PlatformConfig {
        val normalizedAdapter = when (selectedPlatformType()) {
            PlatformType.ONEBOT11 -> normalizeAdapterKind(platform.adapter).serialName()
            PlatformType.QQ_OFFICIAL -> PlatformAdapterKind.QQ_OFFICIAL.serialName()
        }
        return if (platform.adapter == normalizedAdapter) {
            platform
        } else {
            platform.copy(adapter = normalizedAdapter)
        }
    }

    fun normalizedBotConfig(): BotConfig {
        val normalizedPlatform = normalizedPlatformConfig()
        return if (normalizedPlatform == platform) this else copy(platform = normalizedPlatform)
    }

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
            PlatformType.QQ_OFFICIAL -> {
                platform.qqOfficial.appId.isNotBlank() &&
                    platform.qqOfficial.appSecret.isNotBlank()
            }
        }
    }

    private fun normalizeAdapterKind(rawAdapter: String?): PlatformAdapterKind {
        val normalized = rawAdapter?.trim()?.lowercase().orEmpty()
        return when {
            normalized == PlatformAdapterKind.NAPCAT.serialName() -> PlatformAdapterKind.NAPCAT
            normalized == PlatformAdapterKind.ONEBOT11.serialName() -> PlatformAdapterKind.ONEBOT11
            normalized.isBlank() && napcat != NapCatConfig() && platform.onebot11 == NapCatConfig() -> PlatformAdapterKind.NAPCAT
            else -> PlatformAdapterKind.ONEBOT11
        }
    }
}

private fun PlatformAdapterKind.serialName(): String = when (this) {
    PlatformAdapterKind.NAPCAT -> "napcat"
    PlatformAdapterKind.ONEBOT11 -> "onebot11"
    PlatformAdapterKind.QQ_OFFICIAL -> "qq_official"
}
