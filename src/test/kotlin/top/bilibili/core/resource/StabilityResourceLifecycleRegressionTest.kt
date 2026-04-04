package top.bilibili.core.resource

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class StabilityResourceLifecycleRegressionTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `http get should close stream and disconnect in finally`() {
        val source = read("src/main/kotlin/top/bilibili/utils/translate/HttpGet.kt")

        assertTrue(source.contains("finally {"))
        assertTrue(source.contains("close(br)"))
        assertTrue(source.contains("close(`is`)"))
        assertTrue(source.contains("conn?.disconnect()"))
    }

    @Test
    fun `bili client should reject usage after close`() {
        val source = read("src/main/kotlin/top/bilibili/client/BiliClient.kt")

        assertTrue(source.contains("ensureClientOpen()"))
        assertTrue(source.contains("BiliClient has been closed"))
        assertTrue(source.contains("private fun getOrCreateClient(slotIndex: Int): HttpClient"))
    }

    @Test
    fun `bot should track and cancel startup delayed jobs in ingress phase`() {
        val source = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")

        assertTrue(source.contains("startupDataInitJob"))
        assertTrue(source.contains("startupTaskBootstrapJob"))
        assertTrue(source.contains("id = \"startup-delayed-jobs\""))
        assertTrue(source.contains("shutdownPhase = ShutdownPhase.INGRESS"))
        assertTrue(source.contains("cancelAndJoin()"))
    }

    @Test
    fun `send tasker at all warning map should have eviction policy`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/SendTasker.kt")

        assertTrue(source.contains("AT_ALL_WARN_CACHE_MAX_SIZE"))
        assertTrue(source.contains("AT_ALL_WARN_RETAIN_MS"))
        assertTrue(source.contains("cleanupAtAllWarnCache("))
    }

    @Test
    fun `md5 input stream path should close stream with use`() {
        val source = read("src/main/kotlin/top/bilibili/utils/translate/MD5.kt")

        assertTrue(source.contains("fun md5(`in`: InputStream): String?"))
        assertTrue(source.contains("`in`.use { input ->"))
    }
}
