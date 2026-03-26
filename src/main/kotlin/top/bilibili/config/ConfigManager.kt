package top.bilibili.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File

object ConfigManager {
    private val logger = LoggerFactory.getLogger(ConfigManager::class.java)
    private val yaml = Yaml.default

    private val configDir = File("config")
    private val botConfigFile = File(configDir, "bot.yml")

    lateinit var botConfig: BotConfig
        private set

    fun init() {
        if (!configDir.exists()) {
            configDir.mkdirs()
            logger.info("创建配置目录: ${configDir.absolutePath}")
        }

        if (botConfigFile.exists()) {
            try {
                val content = botConfigFile.readText()
                botConfig = yaml.decodeFromString<BotConfig>(content)
                logger.info("成功加载配置文件: ${botConfigFile.name}")
            } catch (e: Exception) {
                logger.error("加载配置文件失败，使用默认配置: ${e.message}", e)
                botConfig = BotConfig()
                saveConfig()
            }
        } else {
            logger.info("配置文件不存在，创建默认配置")
            botConfig = BotConfig()
            saveConfig()
        }

        if (!botConfig.validateSelectedPlatform()) {
            logger.warn("当前平台配置无效，请检查 config/bot.yml 中的 ${botConfig.selectedPlatformType()}")
        }
    }

    fun saveConfig() {
        try {
            val content = yaml.encodeToString(botConfig)
            botConfigFile.writeText(content)
            logger.info("配置已保存到: ${botConfigFile.absolutePath}")
        } catch (e: Exception) {
            logger.error("保存配置失败: ${e.message}", e)
        }
    }

    fun reload() {
        logger.info("正在重新加载配置...")
        init()
    }
}
