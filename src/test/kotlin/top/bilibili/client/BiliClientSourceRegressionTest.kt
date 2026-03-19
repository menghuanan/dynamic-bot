package top.bilibili.client

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class BiliClientSourceRegressionTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `bili client should use structured retry log builders`() {
        val text = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")
        assertTrue(text.contains("buildRetryLogMessage("))
        assertTrue(text.contains("buildRetryExhaustedLogMessage("))
    }

    @Test
    fun `dynamic and live polling should pass source markers into api wrappers`() {
        val dynamicTasker = read("src/main/kotlin/top/bilibili/tasker/DynamicCheckTasker.kt")
        val liveTasker = read("src/main/kotlin/top/bilibili/tasker/LiveCheckTasker.kt")

        assertTrue(dynamicTasker.contains("source = \"DynamicCheckTasker.poll\""))
        assertTrue(dynamicTasker.contains("source = \"DynamicCheckTasker.manual-check\""))
        assertTrue(liveTasker.contains("source = \"LiveCheckTasker.followed-live-list\""))
        assertTrue(liveTasker.contains("source = \"LiveCheckTasker.subscribed-live-status\""))
    }
}
