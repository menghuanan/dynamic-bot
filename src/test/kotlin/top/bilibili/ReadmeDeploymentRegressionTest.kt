package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import top.bilibili.config.BotConfig
import top.bilibili.config.ConfigManager
import top.bilibili.connector.PlatformAdapterKind
import top.bilibili.connector.PlatformType
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReadmeDeploymentRegressionTest {
    private val yaml = Yaml.default

    // 显式触发默认 bot.yml 生成并在结束后恢复现场，避免测试依赖开发机已有运行文件。
    private fun <T> withGeneratedBotConfig(block: (String) -> T): T {
        val configDir = Path.of("config")
        val botConfigPath = configDir.resolve("bot.yml")
        val originalContent = if (Files.exists(botConfigPath)) Files.readAllBytes(botConfigPath) else null

        try {
            Files.createDirectories(configDir)
            Files.deleteIfExists(botConfigPath)
            ConfigManager.init()
            return block(Files.readString(botConfigPath))
        } finally {
            if (originalContent == null) {
                Files.deleteIfExists(botConfigPath)
            } else {
                Files.write(botConfigPath, originalContent)
            }
        }
    }

    @Test
    fun `示例配置应以平台化配置为主路径`() {
        val sampleConfig = withGeneratedBotConfig { generated -> generated }
        val parsedSampleConfig = yaml.decodeFromString<BotConfig>(sampleConfig)

        assertTrue(sampleConfig.contains("platform:"), "示例配置应使用 platform 段")
        assertTrue(sampleConfig.contains("type:"), "示例配置应显式包含平台类型字段")
        assertTrue(sampleConfig.contains("adapter:"), "示例配置应显式包含 adapter 字段")
        assertTrue(sampleConfig.contains("onebot11:"), "示例配置应包含 onebot11 子段")
        assertTrue(sampleConfig.contains("qq_official:"), "示例配置应包含 QQ 官方子段")
        assertEquals(PlatformType.ONEBOT11, parsedSampleConfig.selectedPlatformType(), "示例配置应默认选择 onebot11")
        assertEquals(PlatformAdapterKind.ONEBOT11, parsedSampleConfig.selectedAdapterKind(), "示例配置应默认选择通用 onebot11 adapter")
    }
}
