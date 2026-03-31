package top.bilibili.tasker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ProcessGuardianResourceObservabilityTest {
    // 源码回归测试统一使用 UTF-8，避免不同平台默认编码导致关键字匹配漂移。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `process guardian should collect process rss thread buffer pool skia and degradable nmt metrics`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("VmRSS") || source.contains("resident"),
            "ProcessGuardian should collect RSS-compatible process memory metrics",
        )
        assertTrue(
            source.contains("BufferPoolMXBean"),
            "ProcessGuardian should inspect JVM BufferPool metrics",
        )
        assertTrue(
            source.contains("ThreadMXBean"),
            "ProcessGuardian should inspect JVM thread metrics",
        )
        assertTrue(
            source.contains("SkiaManager.getStatus()"),
            "ProcessGuardian should include Skia runtime status",
        )
        assertTrue(
            source.contains("VM.native_memory summary"),
            "ProcessGuardian should attempt jcmd native memory summary collection",
        )
        assertTrue(
            source.contains("降级") || source.contains("unavailable") || source.contains("不可用"),
            "ProcessGuardian should record why heavyweight native metrics were downgraded",
        )
    }

    @Test
    fun `process guardian should drain jcmd output asynchronously before waiting for exit`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("drainProcessOutputAsync"),
            "ProcessGuardian should start asynchronous jcmd output draining before waitFor",
        )
    }

    @Test
    fun `process guardian should synchronize tasker snapshots before aggregating lifecycle metrics`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("synchronized(taskers)"),
            "ProcessGuardian should take synchronized tasker snapshots before iterating shared taskers list",
        )
    }

    // 轮询链路的 RSS 问题需要在 daemon 日志里直接看到 BiliClient 和 OkHttp 连接池运行态。
    @Test
    fun `process guardian should include bili client and okhttp observability`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("BiliClient.runtimeSnapshot()"),
            "ProcessGuardian should collect BiliClient runtime snapshot",
        )
        assertTrue(
            source.contains("BiliClient / OkHttp"),
            "ProcessGuardian should emit a dedicated BiliClient and OkHttp log section",
        )
    }

    // guardian 需要把平台 transport 的独立 OkHttp 运行态拆成专门段落，避免与 BiliClient 监控混在一起。
    @Test
    fun `process guardian should include dedicated platform transport observability section`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("Platform HttpClient / OkHttp"),
            "ProcessGuardian should emit a dedicated platform transport HttpClient and OkHttp log section",
        )
        assertTrue(
            source.contains("runtimeObservability()"),
            "ProcessGuardian should read platform transport runtime observability snapshots from connector manager",
        )
        assertTrue(
            source.contains("collectPlatformRuntimeObservability()"),
            "ProcessGuardian should centralize platform transport snapshot collection before writing logs",
        )
        assertTrue(
            source.contains("PlatformObservabilitySnapshot.empty(\"platform adapter is not initialized\")"),
            "ProcessGuardian should fall back to an empty platform snapshot when the adapter is not initialized",
        )
        assertTrue(
            source.contains("report.platformObservability.note?.let"),
            "ProcessGuardian should emit the empty snapshot note instead of failing when platform observability is unavailable",
        )
    }
}
