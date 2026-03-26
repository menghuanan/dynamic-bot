package top.bilibili.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.connector.PlatformType

class PlatformConfigCompatibilityTest {
    private val yaml = Yaml.default

    @Test
    fun `legacy napcat config should still resolve into onebot11 platform selection`() {
        val config = yaml.decodeFromString<BotConfig>(
            """
            napcat:
              host: "192.168.1.5"
              port: 3010
              token: "legacy-token"
            """.trimIndent(),
        )

        assertEquals(PlatformType.ONEBOT11, config.selectedPlatformType())
        assertEquals("192.168.1.5", config.selectedOneBot11Config().host)
        assertEquals(3010, config.selectedOneBot11Config().port)
        assertEquals("legacy-token", config.selectedOneBot11Config().token)
    }

    @Test
    fun `qq official config should require app id and secret`() {
        val invalidConfig = yaml.decodeFromString<BotConfig>(
            """
            platform:
              type: qq_official
              qq_official:
                app_id: "demo-app"
            """.trimIndent(),
        )
        val validConfig = yaml.decodeFromString<BotConfig>(
            """
            platform:
              type: qq_official
              qq_official:
                app_id: "demo-app"
                app_secret: "demo-secret"
            """.trimIndent(),
        )

        assertFalse(invalidConfig.validateSelectedPlatform())
        assertTrue(validConfig.validateSelectedPlatform())
    }
}
