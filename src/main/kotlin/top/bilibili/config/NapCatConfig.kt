package top.bilibili.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.EncodeDefault
import top.bilibili.connector.PlatformAdapterKind
import top.bilibili.connector.PlatformType
import top.bilibili.utils.normalizeContactSubject

/**
 * OneBot11/NapCat 连接参数配置。
 */
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
    /**
     * 生成用于建立 WebSocket 连接的地址。
     */
    fun getWebSocketUrl(): String {
        val protocol = if (useTls) "wss" else "ws"
        return "$protocol://$host:$port"
    }

    /**
     * 校验当前 NapCat 配置是否满足最基本的连接要求。
     */
    fun validate(): Boolean {
        val normalizedSendMode = sendMode.lowercase()
        return host.isNotBlank() &&
            port in 1..65535 &&
            normalizedSendMode in setOf("file", "base64")
    }
}

/**
 * 单个推送目标的配置项。
 */
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
        /**
         * 创建群聊目标配置。
         */
        fun group(groupId: Long) = TargetConfig("group", groupId)

        /**
         * 创建私聊目标配置。
         */
        fun private(userId: Long) = TargetConfig("private", userId)
    }
}

/**
 * 单个群管理员映射配置。
 */
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

/**
 * QQ 官方机器人接入配置。
 */
@Serializable
data class QQOfficialConfig(
    @SerialName("app_id")
    val appId: String = "",
    @SerialName("app_secret")
    val appSecret: String = "",
    @SerialName("bot_token")
    val botToken: String = "",
)

/**
 * 当前选中平台的统一配置包装。
 */
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

/**
 * Bot 运行期使用的根配置对象。
 */
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
    /**
     * 返回当前启用的平台类型。
     */
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

    /**
     * 返回适配器字段已经归一化后的平台配置。
     */
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

    /**
     * 返回整体字段已经归一化后的 Bot 配置。
     */
    fun normalizedBotConfig(): BotConfig {
        val normalizedPlatform = normalizedPlatformConfig()
        return if (normalizedPlatform == platform) this else copy(platform = normalizedPlatform)
    }

    /**
     * 在新旧结构并存时，统一解析当前应该使用的 OneBot11 配置。
     */
    fun selectedOneBot11Config(): NapCatConfig {
        // 只有在新版节点仍为空时才回退 legacy 字段，避免旧字段覆盖显式配置的新结构。
        return if (platform.onebot11 == NapCatConfig() && napcat != NapCatConfig()) {
            napcat
        } else {
            platform.onebot11
        }
    }

    /**
     * 校验当前选择的平台配置是否完整可用。
     */
    fun validateSelectedPlatform(): Boolean {
        return when (selectedPlatformType()) {
            PlatformType.ONEBOT11 -> selectedOneBot11Config().validate()
            PlatformType.QQ_OFFICIAL -> {
                platform.qqOfficial.appId.isNotBlank() &&
                    platform.qqOfficial.appSecret.isNotBlank()
            }
        }
    }

    /**
     * 将配置中的适配器文本映射为受支持的适配器枚举。
     */
    private fun normalizeAdapterKind(rawAdapter: String?): PlatformAdapterKind {
        val normalized = rawAdapter?.trim()?.lowercase().orEmpty()
        return when {
            normalized == PlatformAdapterKind.NAPCAT.serialName() -> PlatformAdapterKind.NAPCAT
            normalized == PlatformAdapterKind.LLBOT.serialName() -> PlatformAdapterKind.LLBOT
            normalized == PlatformAdapterKind.ONEBOT11.serialName() -> PlatformAdapterKind.ONEBOT11
            // 空字符串只在旧配置迁移阶段才推断为 napcat，避免把真正缺省值误判为 legacy 适配器。
            normalized.isBlank() && napcat != NapCatConfig() && platform.onebot11 == NapCatConfig() -> PlatformAdapterKind.NAPCAT
            else -> PlatformAdapterKind.ONEBOT11
        }
    }
}

/**
 * 将适配器枚举转换为配置文件中持久化使用的字符串。
 */
private fun PlatformAdapterKind.serialName(): String = when (this) {
    PlatformAdapterKind.NAPCAT -> "napcat"
    PlatformAdapterKind.LLBOT -> "llbot"
    PlatformAdapterKind.ONEBOT11 -> "onebot11"
    PlatformAdapterKind.QQ_OFFICIAL -> "qq_official"
}
