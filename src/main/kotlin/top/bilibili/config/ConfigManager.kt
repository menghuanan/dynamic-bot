package top.bilibili.config

import org.slf4j.LoggerFactory
import java.io.File

object ConfigManager {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)

    private val configDir = File("config")
    // bot.yml 只负责平台接入配置；旧业务配置与运行数据仍由 BiliConfigManager 管理。
    private val store = BotConfigFileStore(configDir)

    lateinit var botConfig: BotConfig
        private set

    fun init() {
        if (!configDir.exists()) {
            configDir.mkdirs()
            logger.info("创建配置目录: ${configDir.absolutePath}")
        }

        try {
            val loadResult = store.loadWithMetadata()
            botConfig = loadResult.config
            if (loadResult.createdDefault) {
                logger.info("配置文件不存在，创建默认配置")
            } else {
                logger.info("成功加载配置文件: bot.yml")
            }
            if (loadResult.rewritten) {
                logger.info("检测到旧版 bot.yml 结构，已自动迁移为 v1.8 标准配置样式")
            }
        } catch (e: Exception) {
            logger.error("加载配置文件失败，使用默认配置: ${e.message}", e)
            botConfig = BotConfig().normalizedBotConfig()
            saveConfig()
        }

        if (!botConfig.validateSelectedPlatform()) {
            logger.warn("当前平台配置无效，请检查 config/bot.yml 中的 ${botConfig.selectedPlatformType()}")
        }
    }

    fun saveConfig() {
        try {
            botConfig = botConfig.normalizedBotConfig()
            store.save(botConfig)
            logger.info("配置已保存到: ${File(configDir, "bot.yml").absolutePath}")
        } catch (e: Exception) {
            logger.error("保存配置失败: ${e.message}", e)
        }
    }

    fun reload() {
        logger.info("正在重新加载配置...")
        init()
    }
}
