package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReadmeDeploymentRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `Docker 部署文档要求先停止容器再修改挂载配置`() {
        val readme = read("README.md")

        assertTrue(
            readme.contains("docker-compose down") || readme.contains("docker stop dynamic-bot"),
            "README 应要求用户先停止容器再修改挂载配置",
        )
        assertFalse(
            readme.contains("docker-compose restart") || readme.contains("docker restart dynamic-bot"),
            "README 不应保留基于 restart 的挂载配置修改指引",
        )
    }

    @Test
    fun `README 和示例配置应以平台化配置为主路径`() {
        val readme = read("README.md")
        val sampleConfig = read("config/bot.yml")

        assertTrue(readme.contains("platform:"), "README 应说明平台化配置")
        assertTrue(readme.contains("type: onebot11"), "README 应展示平台类型选择")
        assertTrue(readme.contains("adapter: napcat"), "README 应明确 NapCat 配置示例")
        assertTrue(readme.contains("adapter: onebot11"), "README 应明确通用 OneBot11 配置示例")
        assertTrue(readme.contains("qq_official:"), "README 应包含 QQ 官方配置占位")
        assertTrue(
            readme.contains("QQ 官方") && (readme.contains("@全体") || readme.contains("公网图片")),
            "README 应说明当前 QQ 官方适配器的能力边界",
        )
        assertTrue(
            readme.contains("当前支持的协议与支持情况") &&
                readme.contains("NapCat") &&
                readme.contains("OneBot11") &&
                readme.contains("显式降级"),
            "README 应新增协议支持情况说明",
        )

        assertTrue(sampleConfig.contains("platform:"), "示例配置应使用 platform 段")
        assertTrue(sampleConfig.contains("type: onebot11"), "示例配置应默认选择 onebot11")
        assertTrue(sampleConfig.contains("adapter: onebot11"), "示例配置应包含 adapter")
        assertTrue(sampleConfig.contains("onebot11:"), "示例配置应包含 onebot11 子段")
        assertTrue(sampleConfig.contains("qq_official:"), "示例配置应包含 QQ 官方子段")
    }
}
