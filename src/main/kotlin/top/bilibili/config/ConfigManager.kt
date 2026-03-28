package top.bilibili.config

import org.slf4j.LoggerFactory
import java.io.File

/**
 * 统一管理 `bot.yml` 的加载、保存与重载流程。
 */
object ConfigManager {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)

    private val configDir = File("config")
    // bot.yml 只负责平台接入配置；旧业务配置与运行数据仍由 BiliConfigManager 管理。
    private val store = BotConfigFileStore(configDir)

    lateinit var botConfig: BotConfig
        private set

    /**
     * 初始化运行期配置，并在必要时创建或迁移 `bot.yml`。
     */
    fun init() {
        if (!configDir.exists()) {
            configDir.mkdirs()
            logger.info("创建配置目录: ${configDir.absolutePath}")
        }

        try {
            // 启动阶段优先保证服务可继续运行，因此异常时回退到默认配置并立即落盘。
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

    /**
     * 将当前运行期配置规范化后保存到磁盘。
     */
    fun saveConfig() {
        try {
            botConfig = botConfig.normalizedBotConfig()
            store.save(botConfig)
            logger.info("配置已保存到: ${File(configDir, "bot.yml").absolutePath}")
        } catch (e: Exception) {
            logger.error("保存配置失败: ${e.message}", e)
        }
    }

    /**
     * 重新执行一次完整的配置初始化流程。
     */
    fun reload() {
        logger.info("正在重新加载配置...")
        init()
    }
}
