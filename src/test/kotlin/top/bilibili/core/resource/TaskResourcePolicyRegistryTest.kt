package top.bilibili.core.resource

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TaskResourcePolicyRegistryTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `validateCoverage passes when all startup tasks are covered`() {
        val tasks = listOf(
            "ListenerTasker",
            "DynamicCheckTasker",
            "LiveCheckTasker",
            "LiveCloseCheckTasker",
            "DynamicMessageTasker",
            "LiveMessageTasker",
            "SendTasker",
            "CacheClearTasker",
            "LogClearTasker",
            "SkiaCleanupTasker",
            "ProcessGuardian",
        )
        TaskResourcePolicyRegistry.validateCoverage(tasks)
    }

    @Test
    fun `validateCoverage fails when startup task is missing policy`() {
        assertFailsWith<IllegalArgumentException> {
            TaskResourcePolicyRegistry.validateCoverage(listOf("UnknownTasker"))
        }
    }

    @Test
    fun `bili check taskers should declare runtime names that match registered policy keys`() {
        val dynamic = read("src/main/kotlin/top/bilibili/tasker/DynamicCheckTasker.kt")
        val live = read("src/main/kotlin/top/bilibili/tasker/LiveCheckTasker.kt")
        val liveClose = read("src/main/kotlin/top/bilibili/tasker/LiveCloseCheckTasker.kt")

        assertTrue(dynamic.contains("BiliCheckTasker(\"DynamicCheckTasker\")"), "dynamic tasker owner should match policy registry key")
        assertTrue(live.contains("BiliCheckTasker(\"LiveCheckTasker\")"), "live tasker owner should match policy registry key")
        assertTrue(liveClose.contains("BiliCheckTasker(\"LiveCloseCheckTasker\")"), "live-close tasker owner should match policy registry key")
    }
}
