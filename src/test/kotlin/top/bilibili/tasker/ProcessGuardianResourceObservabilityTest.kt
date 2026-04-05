package top.bilibili.tasker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
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
        assertTrue(
            source.contains("platform runtime observability unavailable"),
            "ProcessGuardian should downgrade connector-manager race windows to an explicit empty platform snapshot",
        )
        assertTrue(
            source.contains("获取平台连接状态失败，已降级跳过本轮连接检查"),
            "ProcessGuardian should downgrade runtime-status race windows instead of throwing in guardian loop",
        )
    }

    // RSS 与 NMT 的差值是定位 JVM 外驻留增长最直接的指标，守护日志必须显式输出便于长期对比。
    @Test
    fun `process guardian should emit rss minus nmt metric in daemon logs`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("RssMinusNmt"),
            "ProcessGuardian should emit VmRSS minus NMT committed memory in daemon logs",
        )
    }

    // 非堆内存问题排查需要直接看到 Metaspace 与 CodeCache 的细分项，避免只看总量无法定位分区。
    @Test
    fun `process guardian should emit non heap partition breakdown for metaspace and codecache`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("nonHeapBreakdown"),
            "ProcessGuardian should persist non-heap partition breakdown details in monitor report",
        )
        assertTrue(
            source.contains("[非堆细分]"),
            "ProcessGuardian should emit a dedicated non-heap breakdown section in daemon logs",
        )
    }

    // Native 疑似泄漏排查需要把 VmRSS 与 NMT committed 的差值输出为“未归类 native 区”并显式记录。
    @Test
    fun `process guardian should emit unattributed native region estimate from rss and nmt committed`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("unattributedNativeMB"),
            "ProcessGuardian should keep unattributed native region estimate in monitor report",
        )
        assertTrue(
            source.contains("[Native 未归类估算]"),
            "ProcessGuardian should emit a dedicated unattributed native section in daemon logs",
        )
    }

    // Native 分析要求输出全量分区，不能再只保留 top 切片。
    @Test
    fun `process guardian should keep full native section list without top slicing`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("val sections: List<NativeMemorySection>"),
            "ProcessGuardian should persist full NMT section list in summary model",
        )
        assertFalse(
            source.contains(".take(8)"),
            "ProcessGuardian should not truncate native memory sections to top 8",
        )
        assertTrue(
            source.contains("native.sections.forEach"),
            "ProcessGuardian should emit all NMT sections in daemon logs",
        )
    }

    // 任务相关性用于把 Native 增长窗口和任务活动窗口对齐，输出可排查的嫌疑任务排行。
    @Test
    fun `process guardian should emit native task correlation hints`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("collectNativeTaskCorrelations"),
            "ProcessGuardian should compute native task correlation hints",
        )
        assertTrue(
            source.contains("nativeTaskCorrelations"),
            "ProcessGuardian should keep correlation hints in monitor report",
        )
        assertTrue(
            source.contains("[Native 任务相关性]"),
            "ProcessGuardian should emit a dedicated native task correlation section",
        )
    }

    // 非堆排查需要覆盖全部 NON_HEAP 池，避免只看 Metaspace/CodeCache 漏掉其它分区。
    @Test
    fun `process guardian should collect all non heap pools by memory type`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("MemoryType.NON_HEAP"),
            "ProcessGuardian should filter all non-heap pools by MemoryType.NON_HEAP",
        )
    }

    // 线上排障要求“非堆只要增长就记点”，守护进程需要把非堆增长直接升级为可落盘事件。
    @Test
    fun `process guardian should mark non heap growth as immediate log trigger`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("lastNonHeapUsedByPoolNameBytes"),
            "ProcessGuardian should keep last non-heap pool usage snapshot for growth detection",
        )
        assertTrue(
            source.contains("非堆增长"),
            "ProcessGuardian should record non-heap growth details for daemon log emission",
        )
        assertTrue(
            source.contains("hasNonHeapGrowthIssue"),
            "ProcessGuardian should expose explicit non-heap growth issue flag",
        )
    }

    // 非堆排查需要区分“预热期正常上涨”和“预热完成后的长期单调上涨”，避免把启动期噪声误判为长期风险。
    @Test
    fun `process guardian should track non heap warmup completion and expose warmup state`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("nonHeapWarmupCompleted"),
            "ProcessGuardian should persist non-heap warmup completion state in monitor report",
        )
        assertTrue(
            source.contains("NON_HEAP_WARMUP"),
            "ProcessGuardian should define dedicated non-heap warmup thresholds",
        )
        assertTrue(
            source.contains("[非堆趋势]"),
            "ProcessGuardian should emit a dedicated non-heap trend section in daemon logs",
        )
    }

    // 7x24 运行要求在预热完成后识别“长期增长且不回落”并告警，避免只靠瞬时增长事件判断。
    @Test
    fun `process guardian should detect sustained non heap growth after warmup`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("hasNonHeapLongGrowthIssue"),
            "ProcessGuardian should expose explicit sustained non-heap growth issue flag",
        )
        assertTrue(
            source.contains("NON_HEAP_LONG_GROWTH"),
            "ProcessGuardian should define dedicated sustained non-heap growth thresholds",
        )
        assertTrue(
            source.contains("长期增长"),
            "ProcessGuardian should record sustained non-heap growth details in daemon logs",
        )
    }

    // 非堆增长告警在后续回落时需要输出恢复提示，避免只看到告警而看不到缓解结果。
    @Test
    fun `process guardian should emit rollback info after non heap burst or sustained growth`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("检测到非堆增长已回落"),
            "ProcessGuardian should log an info message when burst non-heap growth is fully recovered",
        )
        assertTrue(
            source.contains("检测到非堆增长部分回落"),
            "ProcessGuardian should log a rollback message when burst non-heap growth is partially recovered",
        )
        assertTrue(
            source.contains("检测到非堆长期增长已回落"),
            "ProcessGuardian should log an info message when sustained non-heap growth is recovered",
        )
        assertTrue(
            source.contains("检测到非堆长期增长部分回落"),
            "ProcessGuardian should log a rollback message when sustained non-heap growth is partially recovered",
        )
    }

    // 非堆“部分回落”在 30 秒巡检下可能高频抖动，默认应降级到 DEBUG，避免刷屏挤压关键日志。
    @Test
    fun `process guardian should downgrade noisy non heap partial rollback logs to debug`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("logger.debug(\n                    \"检测到非堆增长部分回落"),
            "ProcessGuardian should downgrade burst-growth partial rollback logs to debug",
        )
        assertTrue(
            source.contains("logger.debug(\n                    \"检测到非堆长期增长部分回落"),
            "ProcessGuardian should downgrade sustained-growth partial rollback logs to debug",
        )
    }

    // 非堆增长告警与回落日志应统一为“基线/当前/峰值”表达，便于值班时做横向对读。
    @Test
    fun `process guardian should align burst and sustained growth warning format with rollback style`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("检测到非堆突发增长: {} 较基线 +{}KB (当前={}KB, 基线={}KB, 峰值+{}KB)"),
            "ProcessGuardian should use baseline/current/peak style for burst non-heap growth warnings",
        )
        assertTrue(
            source.contains("检测到非堆长期增长: {} 较基线 +{}KB (当前={}KB, 基线={}KB, 峰值+{}KB, 最大回落={}KB, 持续={}s)"),
            "ProcessGuardian should use baseline/current/peak style for sustained non-heap growth warnings",
        )
    }

    // Linux 上 RSS 增长需要区分匿名页与文件映射页，守护进程应采集 smaps_rollup 的关键分项。
    @Test
    fun `process guardian should collect smaps rollup anonymous file and shmem metrics`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("/proc/self/smaps_rollup"),
            "ProcessGuardian should read /proc/self/smaps_rollup when available",
        )
        assertTrue(
            source.contains("Anonymous"),
            "ProcessGuardian should parse Anonymous from smaps_rollup",
        )
        assertTrue(
            source.contains("File"),
            "ProcessGuardian should parse File from smaps_rollup",
        )
        assertTrue(
            source.contains("Shmem"),
            "ProcessGuardian should parse Shmem from smaps_rollup",
        )
    }

    // 软限制策略要求在 RSS 连续高位时触发条件重启，避免长期漂移击穿容器上限。
    @Test
    fun `process guardian should provide rss soft limit guard with conditional restart`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(
            source.contains("RSS_SOFT_LIMIT_MB"),
            "ProcessGuardian should define an RSS soft limit threshold",
        )
        assertTrue(
            source.contains("RSS_SOFT_LIMIT_HOLD_MS"),
            "ProcessGuardian should enforce a sustained high-memory window before restart",
        )
        assertTrue(
            source.contains("exitProcess"),
            "ProcessGuardian should trigger process restart when RSS soft limit guard is exceeded",
        )
    }

    // 正常态日志应按 10 分钟窗口去重，避免 30 秒巡检周期在同一分钟重复写盘。
    @Test
    fun `process guardian should de duplicate normal monitor logs within the same minute bucket`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(source.contains("lastNormalLogMinute"))
        assertTrue(source.contains("lastNormalLogMinute != currentMinute"))
    }
}
