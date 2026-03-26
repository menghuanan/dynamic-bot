package top.bilibili.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.connector.PlatformAdapterKind
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

    @Test
    fun `default bot config should serialize explicit generic onebot11 adapter`() {
        val content = yaml.encodeToString(BotConfig())

        assertTrue(content.contains("adapter:"), content)
        assertTrue(content.contains("onebot11"), content)
    }

    @Test
    fun `missing adapter under onebot11 should fall back to generic onebot11 instead of napcat`() {
        val config = yaml.decodeFromString<BotConfig>(
            """
            platform:
              type: onebot11
              onebot11:
                host: "192.168.1.6"
                port: 3020
                token: "generic-token"
            """.trimIndent(),
        )

        assertEquals(PlatformType.ONEBOT11, config.selectedPlatformType())
        assertEquals(PlatformAdapterKind.ONEBOT11, config.selectedAdapterKind())
    }

    @Test
    fun `unknown adapter under onebot11 should fall back to generic onebot11`() {
        val config = yaml.decodeFromString<BotConfig>(
            """
            platform:
              type: onebot11
              adapter: custom_vendor
              onebot11:
                host: "192.168.1.7"
                port: 3030
                token: "custom-token"
            """.trimIndent(),
        )

        assertEquals(PlatformType.ONEBOT11, config.selectedPlatformType())
        assertEquals(PlatformAdapterKind.ONEBOT11, config.selectedAdapterKind())
    }
}
