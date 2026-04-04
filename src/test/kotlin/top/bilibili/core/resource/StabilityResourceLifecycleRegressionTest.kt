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

    @Test
    fun `shared service clients should reject recreation while bot is stopping`() {
        val serviceGeneral = read("src/main/kotlin/top/bilibili/service/General.kt")
        val checkTasker = read("src/main/kotlin/top/bilibili/tasker/BiliCheckTasker.kt")
        val utilsGeneral = read("src/main/kotlin/top/bilibili/utils/General.kt")

        assertTrue(serviceGeneral.contains("if (BiliBiliBot.isStopping())"))
        assertTrue(serviceGeneral.contains("service.shared BiliClient is unavailable while bot is stopping"))

        assertTrue(checkTasker.contains("if (BiliBiliBot.isStopping())"))
        assertTrue(checkTasker.contains("BiliCheckTasker shared BiliClient is unavailable while bot is stopping"))

        assertTrue(utilsGeneral.contains("if (BiliBiliBot.isStopping())"))
        assertTrue(utilsGeneral.contains("utils.shared BiliClient is unavailable while bot is stopping"))
        assertTrue(utilsGeneral.contains("fun closeUtilsClient()"))
    }

    @Test
    fun `message command router should stop scheduling async commands during shutdown`() {
        val source = read("src/main/kotlin/top/bilibili/service/MessageCommandRouterService.kt")

        assertTrue(source.contains("if (BiliBiliBot.isStopping())"))
        assertTrue(source.contains("停机期间忽略命令异步任务"))
    }

    @Test
    fun `process guardian jcmd probe should not leak short lived process handles`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(source.contains("probeJcmdInPath()"))
        assertTrue(source.contains("closeProcessHandles(process)"))
        assertTrue(source.contains("private fun closeProcessHandles(process: Process)"))
        assertTrue(source.contains("probe.destroyForcibly()"))
        assertTrue(source.contains("probe.inputStream.close()"))
        assertTrue(source.contains("probe.errorStream.close()"))
        assertTrue(source.contains("probe.outputStream.close()"))
        assertTrue(source.contains("probe.waitFor("))
        kotlin.test.assertFalse(source.contains("ProcessBuilder(it, \"-h\").start().destroy()"))
    }

    @Test
    fun `tasker cancel should clear managed worker metadata`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/BiliTasker.kt")

        assertTrue(source.contains("managedWorkers.clear()"))
        assertTrue(source.contains("managedWorkerDefinitions.clear()"))
    }

    @Test
    fun `tasker start should reject restart during shutdown and duplicate activation`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/BiliTasker.kt")

        assertTrue(source.contains("if (BiliBiliBot.isStopping())"))
        assertTrue(source.contains("停机阶段拒绝启动任务"))
        assertTrue(source.contains("if (job?.isActive == true)"))
        assertTrue(source.contains("任务已在运行，忽略重复启动"))
    }
}
