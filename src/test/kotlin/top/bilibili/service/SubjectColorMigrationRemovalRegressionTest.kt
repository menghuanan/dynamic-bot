package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class SubjectColorMigrationRemovalRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `startup should not wire subject color migration anymore`() {
        val configManager = read("src/main/kotlin/top/bilibili/BiliConfigManager.kt")
        val bot = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")

        assertFalse(
            configManager.contains("migrateSubjectScopedColorBindings"),
            "BiliConfigManager should no longer run startup subject color migration",
        )
        assertFalse(
            configManager.contains("pendingSubjectColorMigrationNotice"),
            "BiliConfigManager should no longer store migration notices",
        )
        assertFalse(
            bot.contains("consumePendingSubjectColorMigrationNotice"),
            "BiliBiliBot should not consume removed migration notices during startup",
        )
        assertFalse(
            bot.contains("subject color migration summary"),
            "BiliBiliBot should not send subject color migration summaries during startup",
        )
    }
}
