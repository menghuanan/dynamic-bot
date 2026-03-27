package top.bilibili.config

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliConfigManager
import top.bilibili.BiliData
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

    @Test
    fun `llbot adapter under onebot11 should resolve into explicit llbot selection`() {
        val config = yaml.decodeFromString<BotConfig>(
            """
            platform:
              type: onebot11
              adapter: llbot
              onebot11:
                host: "192.168.1.8"
                port: 3040
                token: "llbot-token"
            """.trimIndent(),
        )

        assertEquals(PlatformType.ONEBOT11, config.selectedPlatformType())
        assertEquals("LLBOT", config.selectedAdapterKind().name)
        assertEquals("llbot", config.normalizedPlatformConfig().adapter)
    }

    @Test
    fun `legacy numeric blacklist data should migrate into namespaced contacts only once`() {
        BiliData.linkParseBlacklist.clear()
        BiliData.linkParseBlacklistContacts.clear()
        BiliData.linkParseBlacklist += setOf(10001L, 20002L)

        val migrate = BiliConfigManager::class.java.getDeclaredMethod("migrateDataIfNeeded", BiliData::class.java)
        migrate.isAccessible = true

        val firstChanged = migrate.invoke(BiliConfigManager, BiliData) as Boolean
        val secondChanged = migrate.invoke(BiliConfigManager, BiliData) as Boolean

        assertTrue(firstChanged)
        assertFalse(secondChanged)
        assertEquals(
            setOf("onebot11:private:10001", "onebot11:private:20002"),
            BiliData.linkParseBlacklistContacts,
        )
    }
}
