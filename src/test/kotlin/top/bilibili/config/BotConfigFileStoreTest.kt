package top.bilibili.config

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BotConfigFileStoreTest {
    private val tempRoot: Path = Files.createTempDirectory("bot-config-store")

    @AfterTest
    fun cleanup() {
        tempRoot.toFile().deleteRecursively()
    }

    @Test
    fun `load should rewrite legacy napcat config into canonical bot yml while preserving values`() {
        val configDir = Files.createDirectories(tempRoot.resolve("config"))
        val botFile = configDir.resolve("bot.yml")
        Files.writeString(
            botFile,
            """
            napcat:
              host: "host.docker.internal"
              port: 6199
              token: ".NK@FFlU4@,WBM39"
              use_tls: false
            targets:
            - type: "group"
              id: 10086
            admins:
            - groupId: 1072150397
              userIds:
              - 793122294
            first_run_flag: 1
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        val store = BotConfigFileStore(configDir.toFile())

        val config = store.load()
        val rewritten = Files.readString(botFile, StandardCharsets.UTF_8)

        assertEquals("host.docker.internal", config.selectedOneBot11Config().host)
        assertEquals(6199, config.selectedOneBot11Config().port)
        assertEquals(".NK@FFlU4@,WBM39", config.selectedOneBot11Config().token)
        assertEquals(1, config.targets.size)
        assertEquals(1, config.admins.size)
        assertEquals(1, config.firstRunFlag)
        assertTrue(rewritten.contains("platform:"), rewritten)
        assertTrue(rewritten.contains("onebot11:"), rewritten)
        assertFalse(rewritten.contains("\nnapcat:"), rewritten)
    }

    @Test
    fun `load should create canonical default bot yml without duplicate legacy napcat block`() {
        val configDir = tempRoot.resolve("default-config").toFile()
        val store = BotConfigFileStore(configDir)

        val config = store.load()
        val saved = Files.readString(configDir.toPath().resolve("bot.yml"), StandardCharsets.UTF_8)

        assertEquals("onebot11", config.platform.adapter)
        assertTrue(saved.contains("platform:"), saved)
        assertTrue(saved.contains("adapter:"), saved)
        assertFalse(saved.contains("\nnapcat:"), saved)
    }

    @Test
    fun `save should write canonical onebot11 config only once when adapter is napcat`() {
        val configDir = tempRoot.resolve("save-config").toFile()
        val store = BotConfigFileStore(configDir)
        val config = BotConfig(
            platform = PlatformConfig(
                adapter = "napcat",
            ),
            napcat = NapCatConfig(
                host = "127.0.0.1",
                port = 3001,
                token = "napcat-token",
            ),
            firstRunFlag = 1,
        )

        store.save(config)
        val saved = Files.readString(configDir.toPath().resolve("bot.yml"), StandardCharsets.UTF_8)

        assertTrue(
            saved.contains("adapter: \"napcat\"") || saved.contains("adapter: napcat"),
            saved,
        )
        assertTrue(saved.contains("onebot11:"), saved)
        assertTrue(saved.contains("token: \"napcat-token\""), saved)
        assertFalse(saved.contains("\nnapcat:"), saved)
    }
}
