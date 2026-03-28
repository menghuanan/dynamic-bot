package top.bilibili.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 描述一次 bot 配置文件加载的结果及其副作用。
 */
internal data class BotConfigFileLoadResult(
    val config: BotConfig,
    val createdDefault: Boolean = false,
    val rewritten: Boolean = false,
)

/**
 * 负责以统一格式读写 `bot.yml`。
 */
internal class BotConfigFileStore(
    private val configDir: File,
    private val yaml: Yaml = Yaml.default,
) {
    private val botConfigFile = File(configDir, "bot.yml")

    /**
     * 统一加载 bot.yml；缺失时生成默认文件，旧结构命中时自动按标准结构写回。
     */
    fun loadWithMetadata(): BotConfigFileLoadResult {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }

        if (!botConfigFile.exists()) {
            val defaultConfig = BotConfig().normalizedBotConfig()
            save(defaultConfig)
            return BotConfigFileLoadResult(config = defaultConfig, createdDefault = true)
        }

        val content = botConfigFile.readText(StandardCharsets.UTF_8)
        val decodeResult = yaml.decodeFromString<BotConfigCompatDocument>(content).toLoadResult(content)
        if (decodeResult.requiresRewrite) {
            save(decodeResult.config)
        }
        return BotConfigFileLoadResult(
            config = decodeResult.config,
            rewritten = decodeResult.requiresRewrite,
        )
    }

    /**
     * 为测试和调用方保留最小加载入口，屏蔽写回元信息。
     */
    fun load(): BotConfig = loadWithMetadata().config

    /**
     * 只按 v1.8 标准结构写出平台配置，避免继续把 legacy napcat 块持久化回 bot.yml。
     */
    fun save(config: BotConfig) {
        if (!configDir.exists()) {
            configDir.mkdirs()
        }
        val canonicalConfig = CanonicalBotConfigDocument.fromRuntimeConfig(config.normalizedBotConfig())
        val content = yaml.encodeToString(canonicalConfig)
        botConfigFile.writeText(content, StandardCharsets.UTF_8)
    }
}

private data class BotConfigDecodeResult(
    val config: BotConfig,
    val requiresRewrite: Boolean,
)

/**
 * 兼容旧版 `bot.yml` 结构的中间解码模型。
 */
@Serializable
private data class BotConfigCompatDocument(
    val platform: PlatformCompatDocument = PlatformCompatDocument(),
    val napcat: NapCatConfig = NapCatConfig(),
    val targets: MutableList<TargetConfig> = mutableListOf(),
    val admins: MutableList<GroupAdminConfig> = mutableListOf(),
    @SerialName("first_run_flag")
    val firstRunFlag: Int = 0,
) {
    /**
     * 将兼容输入模型归一到运行时配置，并给出是否需要标准化写回的判定。
     */
    fun toLoadResult(sourceText: String): BotConfigDecodeResult {
        val runtimeConfig = BotConfig(
            platform = platform.toRuntimeConfig(),
            napcat = napcat,
            targets = targets,
            admins = admins,
            firstRunFlag = firstRunFlag,
        ).normalizedBotConfig()
        val canonicalPlatform = CanonicalBotConfigDocument.fromRuntimeConfig(runtimeConfig).platform
        val rewriteRequired =
            hasLegacyNapcatBlock(sourceText) ||
                platform.adapter == null ||
                canonicalPlatform != platform.toRuntimeConfig()
        return BotConfigDecodeResult(
            config = runtimeConfig,
            requiresRewrite = rewriteRequired,
        )
    }
}

/**
 * 平台配置的兼容解码模型。
 */
@Serializable
private data class PlatformCompatDocument(
    val type: top.bilibili.connector.PlatformType = top.bilibili.connector.PlatformType.ONEBOT11,
    @SerialName("adapter")
    val adapter: String? = null,
    val onebot11: NapCatConfig = NapCatConfig(),
    @SerialName("qq_official")
    val qqOfficial: QQOfficialConfig = QQOfficialConfig(),
) {
    /**
     * 将兼容输入里的可空 adapter 还原成运行时平台配置，后续统一交给归一化逻辑处理。
     */
    fun toRuntimeConfig(): PlatformConfig {
        return PlatformConfig(
            type = type,
            adapter = adapter.orEmpty(),
            onebot11 = onebot11,
            qqOfficial = qqOfficial,
        )
    }
}

/**
 * 写回磁盘时使用的标准配置结构。
 */
@Serializable
private data class CanonicalBotConfigDocument(
    val platform: PlatformConfig = PlatformConfig(),
    val targets: MutableList<TargetConfig> = mutableListOf(),
    val admins: MutableList<GroupAdminConfig> = mutableListOf(),
    @SerialName("first_run_flag")
    val firstRunFlag: Int = 0,
) {
    companion object {
        /**
         * 运行时配置写盘前统一收口到唯一标准结构，确保 OneBot11 只保留 platform.onebot11 一份配置。
         */
        fun fromRuntimeConfig(config: BotConfig): CanonicalBotConfigDocument {
            val normalized = config.normalizedBotConfig()
            val platformConfig = if (normalized.selectedPlatformType() == top.bilibili.connector.PlatformType.ONEBOT11) {
                normalized.normalizedPlatformConfig().copy(onebot11 = normalized.selectedOneBot11Config())
            } else {
                normalized.normalizedPlatformConfig()
            }
            return CanonicalBotConfigDocument(
                platform = platformConfig,
                targets = normalized.targets,
                admins = normalized.admins,
                firstRunFlag = normalized.firstRunFlag,
            )
        }
    }
}

/**
 * 仅把顶层 legacy napcat 块视为需要迁移写回的旧结构信号，避免误伤嵌套配置项。
 */
private fun hasLegacyNapcatBlock(sourceText: String): Boolean {
    return Regex("""(?m)^napcat\s*:""").containsMatchIn(sourceText)
}
