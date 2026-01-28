package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.encodeToString
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths

/**
 * 配置和数据管理器
 * 负责加载和保存 BiliConfig 和 BiliData
 */
object BiliConfigManager {
    private val logger = LoggerFactory.getLogger(BiliConfigManager::class.java)

    lateinit var config: BiliConfig
        private set

    lateinit var data: BiliData
        private set

    // 配置文件和数据文件路径
    private val configDir = Paths.get("config").toFile()
    private val dataDir = Paths.get("data").toFile()

    private val configFile = File(configDir, "BiliConfig.yml")
    private val dataFile = File(configDir, "BiliData.yml")  // 数据文件也保存到 config 目录

    // YAML 序列化器（忽略未知属性以支持旧配置文件）
    private val yaml = Yaml(
        configuration = Yaml.default.configuration.copy(
            strictMode = false
        )
    )

    /**
     * 初始化配置和数据
     * 如果文件不存在，则创建默认配置
     */
    fun init() {
        // 确保目录存在
        configDir.mkdirs()
        dataDir.mkdirs()

        // 加载配置
        config = loadConfig()
        logger.info("配置加载完成")

        // 加载数据
        data = loadData()
        logger.info("数据加载完成")
    }

    /**
     * 加载配置文件
     * 如果文件不存在，创建默认配置
     */
    private fun loadConfig(): BiliConfig {
        return try {
            if (configFile.exists()) {
                val content = configFile.readText()
                yaml.decodeFromString(BiliConfig.serializer(), content)
            } else {
                logger.info("配置文件不存在，创建默认配置")
                val defaultConfig = BiliConfig()
                saveConfig(defaultConfig)
                defaultConfig
            }
        } catch (e: Exception) {
            logger.error("加载配置文件失败，使用默认配置", e)
            BiliConfig()
        }
    }

    /**
     * 加载数据文件
     * 如果文件不存在，创建默认数据
     * 加载后会更新 BiliData 全局单例
     */
    private fun loadData(): BiliData {
        return try {
            // 检查是否需要从旧位置迁移数据
            val oldDataFile = File(dataDir, "BiliData.yml")
            if (!dataFile.exists() && oldDataFile.exists()) {
                logger.info("检测到旧数据文件，正在迁移到新位置...")
                oldDataFile.copyTo(dataFile, overwrite = false)
                logger.info("数据文件已从 ${oldDataFile.absolutePath} 迁移到 ${dataFile.absolutePath}")
            }

            if (dataFile.exists()) {
                logger.info("从 ${dataFile.absolutePath} 加载数据")
                val content = dataFile.readText()

                // 检查文件是否为空或只有 {}
                if (content.isBlank() || content.trim() == "{}" || content.trim() == "{}") {
                    logger.warn("数据文件为空，使用默认数据")
                    return BiliData
                }

                // 使用包装类进行反序列化
                val loadedWrapper = yaml.decodeFromString(BiliDataWrapper.serializer(), content)

                // 应用到 BiliData 全局单例
                BiliDataWrapper.applyTo(loadedWrapper, BiliData)

                logger.info("数据加载完成：${BiliData.dynamic.size} 个订阅，${BiliData.group.size} 个分组")
                BiliData
            } else {
                logger.info("数据文件不存在，创建默认数据")
                saveData(BiliData)
                BiliData
            }
        } catch (e: Exception) {
            logger.error("加载数据文件失败，使用默认数据", e)
            BiliData
        }
    }

    /**
     * 保存配置到文件
     */
    fun saveConfig(configToSave: BiliConfig = config) {
        try {
            val yamlContent = yaml.encodeToString(BiliConfig.serializer(), configToSave)
            configFile.writeText(yamlContent)
            logger.debug("配置已保存")
        } catch (e: Exception) {
            logger.error("保存配置文件失败", e)
        }
    }

    /**
     * 保存数据到文件
     * 默认保存 BiliData 全局单例（而不是本地 data 副本）
     */
    fun saveData(dataToSave: BiliData = BiliData) {
        try {
            logger.info("准备保存数据：")
            logger.info("- dynamic 数量: ${BiliData.dynamic.size}")
            logger.info("- group 数量: ${BiliData.group.size}")
            logger.info("- dynamic 内容: ${BiliData.dynamic.keys.joinToString()}")

            // 使用包装类进行序列化
            val wrapper = BiliDataWrapper.from(dataToSave)
            val yamlContent = yaml.encodeToString(BiliDataWrapper.serializer(), wrapper)

            // 修复安全漏洞：不再输出可能包含敏感信息的 YAML 内容
            logger.debug("配置数据已序列化，大小: ${yamlContent.length} 字符")

            dataFile.writeText(yamlContent)
            logger.info("数据已保存到 ${dataFile.absolutePath}")

            // 验证保存
            val savedContent = dataFile.readText()
            if (savedContent.trim() == "{}" || savedContent.trim() == "{}") {
                logger.error("警告：保存的文件为空！")
            } else {
                logger.info("文件验证：保存成功，大小 ${savedContent.length} 字节")
            }
        } catch (e: Exception) {
            logger.error("保存数据文件失败", e)
        }
    }

    /**
     * 保存所有配置和数据
     */
    fun saveAll() {
        saveConfig()
        saveData()
    }

    /**
     * 重新加载配置
     */
    fun reloadConfig() {
        config = loadConfig()
        logger.info("配置已重新加载")
    }

    /**
     * 重新加载数据
     * 会更新 BiliData 全局单例
     */
    fun reloadData() {
        data = loadData()
        logger.info("数据已重新加载")
    }

    /**
     * 重新加载所有配置和数据
     */
    fun reloadAll() {
        reloadConfig()
        reloadData()
    }
}
