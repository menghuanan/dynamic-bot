package top.bilibili

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse

class SourceTextRegressionTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `shutdown hardening changes should not introduce english or mojibake log text`() {
        val checks = mapOf(
            "src/main/kotlin/top/bilibili/core/BiliBiliBot.kt" to listOf(
                "Bot is already running, ignoring duplicate start request",
                "Debug mode enabled from",
                "Welcome to BiliBili Dynamic Bot",
                "Initializing required directories...",
                "Bot started successfully",
                "Shutdown summary: reason=",
            ),
            "src/main/kotlin/top/bilibili/core/resource/BusinessLifecycleManager.kt" to listOf(
                "Business lifecycle cleanup failed",
                "Business lifecycle cleanup completed with failures",
                "Business lifecycle session close failed",
            ),
            "src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt" to listOf(
                "NapCat WebSocket client is already stopping",
                "NapCat connect loop stopped during shutdown",
                "NapCat receive loop stopped during shutdown",
                "Skipping sendMessage during shutdown",
                "Skipping login info request during shutdown",
                "鍏抽棴 WebSocket 浼氳瘽澶辫触",
            ),
            "src/main/kotlin/top/bilibili/tasker/BiliTasker.kt" to listOf(
                "stopped during shutdown",
            ),
            "src/main/kotlin/top/bilibili/tasker/DynamicMessageTasker.kt" to listOf(
                "dynamicChannel closed",
            ),
            "src/main/kotlin/top/bilibili/tasker/LiveMessageTasker.kt" to listOf(
                "liveChannel closed",
            ),
            "src/main/kotlin/top/bilibili/tasker/SendTasker.kt" to listOf(
                "SendTasker queue processor stopped during shutdown",
                "SendTasker message dispatcher stopped during shutdown",
                "Dropping queued message during shutdown",
                "Dropping pending message during shutdown",
            ),
        )

        checks.forEach { (path, forbiddenSnippets) ->
            val text = read(path)
            forbiddenSnippets.forEach { snippet ->
                assertFalse(
                    text.contains(snippet),
                    "unexpected source text in $path: $snippet",
                )
            }
        }
    }
}
