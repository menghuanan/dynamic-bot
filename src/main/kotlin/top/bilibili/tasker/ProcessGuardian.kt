package top.bilibili.tasker

import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import top.bilibili.client.BiliClient
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.BusinessOwnerActivitySnapshot
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.ImageCache
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.lang.management.ThreadMXBean
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * 综合守护进程
 * 功能：
 * 1. 任务健康监控
 * 2. 内存使用监控
 * 3. 连接状态监控
 * 4. 僵尸任务清理
 * 5. Channel 背压监控 (✅ P3修复)
 * 6. 监控日志记录
 *
 * 特点：
 * - 随程序启动持久化运行
 * - 无资源泄漏
 * - 监控信息写入专门的日志文件
 */
object ProcessGuardian : BiliTasker("ProcessGuardian") {
    override var interval: Int = 30  // 每30秒检查一次

    private val logger = LoggerFactory.getLogger(ProcessGuardian::class.java)

    // 日志目录
    private val logDir = File("logs/daemon").apply {
        if (!exists()) mkdirs()
    }

    // 日期格式化器
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // 内存使用阈值
    private const val WARNING_THRESHOLD = 0.7   // 70%
    private const val CRITICAL_THRESHOLD = 0.85  // 85%

    // Metaspace/CodeCache 阈值 (基于配置的限制)
    private const val METASPACE_LIMIT_MB = 48L
    private const val CODECACHE_LIMIT_MB = 32L
    private const val NON_HEAP_WARNING_THRESHOLD = 0.8  // 80%
    private const val NON_HEAP_GROWTH_LOG_MIN_BYTES = 8L * 1024L
    private const val NON_HEAP_GROWTH_BURST_WARN_BYTES = 256L * 1024L
    private const val NON_HEAP_TREND_MAX_SAMPLES = 240
    private const val NON_HEAP_WARMUP_WINDOW_SAMPLES = 20
    private const val NON_HEAP_WARMUP_STABLE_DRIFT_BYTES = 256L * 1024L
    private const val NON_HEAP_WARMUP_MAX_NET_GROWTH_BYTES = 128L * 1024L
    private const val NON_HEAP_LONG_GROWTH_WINDOW_SAMPLES = 40
    private const val NON_HEAP_LONG_GROWTH_MIN_INCREASE_METASPACE_BYTES = 256L * 1024L
    private const val NON_HEAP_LONG_GROWTH_MIN_INCREASE_CODECACHE_BYTES = 768L * 1024L
    private const val NON_HEAP_LONG_GROWTH_MAX_RETRACE_METASPACE_BYTES = 128L * 1024L
    private const val NON_HEAP_LONG_GROWTH_MAX_RETRACE_CODECACHE_BYTES = 256L * 1024L
    private const val NATIVE_MEMORY_SAMPLE_INTERVAL_MS = 10 * 60 * 1000L
    private const val NATIVE_MEMORY_COMMAND_TIMEOUT_MS = 5_000L
    private const val NATIVE_MEMORY_DESTROY_WAIT_MS = 500L
    private const val PROCESS_OUTPUT_DRAIN_TIMEOUT_MS = 1_000L
    private const val NATIVE_TASK_CORRELATION_LIMIT = 8
    private const val RSS_SOFT_LIMIT_MB = 300L
    private const val RSS_SOFT_LIMIT_HOLD_MS = 30 * 60 * 1000L
    private const val RSS_SOFT_LIMIT_WARN_AFTER_MS = 10 * 60 * 1000L
    private const val RSS_SOFT_LIMIT_RESTART_EXIT_CODE = 78

    // 连接状态追踪
    private var lastConnectionStatus = true
    private var disconnectedDuration = 0
    private var lastNativeMemorySampleAtMillis = 0L
    private var lastNativeMemorySummary: NativeMemorySummary? = null
    private var lastNonHeapUsedByPoolNameBytes: Map<String, Long> = emptyMap()
    private val nonHeapTrendSamples = ArrayDeque<NonHeapTrendSample>()
    private var nonHeapWarmupCompleted = false
    private var nonHeapWarmupCompletedAtMillis = 0L
    private var lastBusinessOwnerRunTotals: Map<String, Long> = emptyMap()
    private var rssAboveSoftLimitSinceMillis = 0L
    private var lastNormalLogMinute = -1L

    override fun init() {
        logger.info("ProcessGuardian 守护进程已启动")
        logger.info("监控间隔: ${interval}秒")
        logger.info("日志目录: ${logDir.absolutePath}")
    }

    override suspend fun main() {
        if (!isActive) return
        if (BiliBiliBot.isStopping()) return

        logger.debug("开始系统健康检查...")

        // 收集监控数据
        val report = MonitorReport()

        // 1. 检查任务健康状态
        checkTaskerHealth(report)

        // 1.5 对仍存活但已不健康的 tasker 走显式恢复路径，而不是只记录告警。
        recoverUnhealthyTaskers()

        // 2. 检查内存使用
        checkMemoryUsage(report)

        // 2.5 检查非堆内存 (Metaspace, CodeCache)
        checkNonHeapMemory(report)

        // 2.8 采集进程、线程、受管协程和 Skia 运行期资源快照
        collectRuntimeResourceSnapshot(report)

        // 3. 检查连接状态
        checkConnectionStatus(report)

        // 4. 清理僵尸任务
        cleanDeadTaskers(report)

        // 5. ✅ P3修复: 检查 Channel 背压
        checkChannelBackpressure(report)

        // 5.5 按异常或固定周期采集一次可降级的 NMT 摘要。
        captureNativeMemorySummary(report)

        // 5.6 仅在本轮完成 native 重采样时执行任务相关性推断，避免每轮都做无意义评分。
        collectNativeTaskCorrelations(report)

        // 5.8 基于 RSS 连续高位触发软限制告警，并在满足窗口后执行条件重启。
        evaluateRssSoftLimit(report)

        // 6. 写入监控日志（只在有异常时写入，或每10分钟写入一次状态）
        writeMonitorLog(report)

        // 6.5 先写入守护日志再执行重启，确保排障信息在触发退出前落盘。
        executeRssSoftLimitRestart(report)

        logger.debug("系统健康检查完成")
    }

    /**
     * 检查任务健康状态
     */
    private fun checkTaskerHealth(report: MonitorReport) {
        val taskerSnapshot = snapshotTaskers()
        val unhealthyTaskers = taskerSnapshot
            .filter { it != this }
            .map { tasker -> tasker.healthSnapshot() }
            .filter { snapshot -> !snapshot.healthy }

        if (unhealthyTaskers.isNotEmpty()) {
            report.hasTaskerIssue = true
            report.deadTaskerNames = unhealthyTaskers.map { snapshot ->
                val workerSummary = snapshot.workerSnapshots
                    .filter { worker -> worker.restartExhausted || worker.lastFailureMessage != null }
                    .joinToString(separator = ",") { worker ->
                        "${worker.workerName}:${worker.lastFailureMessage ?: "inactive"}"
                    }
                if (workerSummary.isBlank()) snapshot.taskerName else "${snapshot.taskerName}[$workerSummary]"
            }
            logger.warn("发现 ${unhealthyTaskers.size} 个异常任务: ${report.deadTaskerNames}")
        } else {
            report.hasTaskerIssue = false
            logger.debug("所有任务运行正常，活跃任务数: ${taskerSnapshot.count { it.healthSnapshot().healthy }}")
        }
    }

    /**
     * 基于 health snapshot 恢复仍存活但 worker 已失效的 tasker；已终止的 shell job 仍交给僵尸清理处理。
     */
    fun recoverUnhealthyTaskers(): List<String> {
        val recoveredTaskers = snapshotTaskers()
            .filter { it != this }
            .map { tasker -> tasker to tasker.healthSnapshot() }
            .filter { (_, snapshot) -> snapshot.active && !snapshot.healthy }
            .mapNotNull { (tasker, snapshot) ->
                if (tasker.recoverUnhealthyWorkers()) snapshot.taskerName else null
            }

        if (recoveredTaskers.isNotEmpty()) {
            logger.warn("已触发异常任务恢复: {}", recoveredTaskers)
        }
        return recoveredTaskers
    }

    /**
     * 检查内存使用
     */
    private fun checkMemoryUsage(report: MonitorReport) {
        val runtime = Runtime.getRuntime()
        val maxMemory = runtime.maxMemory()
        val totalMemory = runtime.totalMemory()
        val freeMemory = runtime.freeMemory()
        val usedMemory = totalMemory - freeMemory

        val usedMB = usedMemory / 1024 / 1024
        val maxMB = maxMemory / 1024 / 1024
        val usageRatio = usedMemory.toDouble() / maxMemory
        val usagePercent = (usageRatio * 100).toInt()

        // 记录内存使用信息
        report.memoryUsedMB = usedMB
        report.memoryMaxMB = maxMB
        report.memoryUsagePercent = usagePercent

        // 收集内存占用最高的组件信息
        report.topMemoryConsumers = collectTopMemoryConsumers()

        logger.debug("内存使用: $usedMB MB / $maxMB MB ($usagePercent%)")

        when {
            usageRatio > CRITICAL_THRESHOLD -> {
                report.hasMemoryIssue = true
                report.memoryIssueLevel = "CRITICAL"
                logger.error("内存使用率严重过高 ($usagePercent%)，触发紧急清理")
                emergencyCleanup()
            }
            usageRatio > WARNING_THRESHOLD -> {
                report.hasMemoryIssue = true
                report.memoryIssueLevel = "WARNING"
                logger.warn("内存使用率过高 ($usagePercent%)，触发清理")
                normalCleanup()
            }
            else -> {
                report.hasMemoryIssue = false
                logger.debug("内存使用正常 ($usagePercent%)")
            }
        }
    }

    /**
     * 检查非堆内存 (Metaspace, CodeCache)
     * 这些区域不受 GC 管理，需要单独监控
     */
    private fun checkNonHeapMemory(report: MonitorReport) {
        val memoryPools = ManagementFactory.getMemoryPoolMXBeans()
            .filter { pool -> pool.type == MemoryType.NON_HEAP }
        val nonHeapBreakdown = mutableListOf<NonHeapPartitionUsage>()
        val currentNonHeapUsedByPoolNameBytes = mutableMapOf<String, Long>()
        var metaspaceUsedBytes = 0L
        var codeCacheUsedBytes = 0L

        for (pool in memoryPools) {
            val usage = pool.usage ?: continue
            currentNonHeapUsedByPoolNameBytes[pool.name] = usage.used
            val usedMB = usage.used / 1024 / 1024
            val maxMB = usage.max
                .takeIf { it > 0L }
                ?.div(1024L * 1024L)
            val usagePercent = maxMB
                ?.takeIf { it > 0L }
                ?.let { limit -> ((usedMB * 100L) / limit).toInt() }

            // 非堆细分需要保留全部 NON_HEAP 分区，避免只看热点区域导致漏判。
            nonHeapBreakdown.add(
                NonHeapPartitionUsage(
                    name = pool.name,
                    usedMB = usedMB,
                    maxMB = maxMB,
                    usagePercent = usagePercent,
                ),
            )

            when {
                pool.name.contains("Metaspace", ignoreCase = true) -> {
                    metaspaceUsedBytes += usage.used
                    report.metaspaceUsedMB = usedMB
                    val ratio = usedMB.toDouble() / METASPACE_LIMIT_MB
                    if (ratio > NON_HEAP_WARNING_THRESHOLD) {
                        report.hasNonHeapIssue = true
                        report.nonHeapIssueDetails.add("Metaspace: ${usedMB}MB / ${METASPACE_LIMIT_MB}MB (${(ratio * 100).toInt()}%)")
                        logger.warn("Metaspace 使用率过高: ${usedMB}MB / ${METASPACE_LIMIT_MB}MB")
                    }
                }
                pool.name.contains("CodeCache", ignoreCase = true) ||
                pool.name.contains("CodeHeap", ignoreCase = true) -> {
                    codeCacheUsedBytes += usage.used
                    report.codeCacheUsedMB += usedMB
                }
            }
        }
        // 输出 Metaspace / CodeCache 的分区细分，用于定位是单一 code heap 还是总量抬升。
        report.nonHeapBreakdown = nonHeapBreakdown.sortedByDescending { it.usedMB }
        // 非堆增长按“任意分区正向增长即记点”执行，确保排障不会错过关键时间窗口。
        val nonHeapGrowthEntries = detectNonHeapGrowth(
            previousUsageByPoolNameBytes = lastNonHeapUsedByPoolNameBytes,
            currentUsageByPoolNameBytes = currentNonHeapUsedByPoolNameBytes,
        )
        if (nonHeapGrowthEntries.isNotEmpty()) {
            val nonHeapGrowthDetails = nonHeapGrowthEntries.map { growth ->
                "${growth.poolName} +${growth.deltaBytes / 1024L}KB " +
                    "(from ${growth.previousBytes / 1024L}KB to ${growth.currentBytes / 1024L}KB)"
            }
            val hasBurstGrowth = nonHeapGrowthEntries.any { growth -> growth.deltaBytes >= NON_HEAP_GROWTH_BURST_WARN_BYTES }
            report.nonHeapGrowthDetails = nonHeapGrowthDetails
            if (!nonHeapWarmupCompleted || hasBurstGrowth) {
                report.hasNonHeapIssue = true
                report.hasNonHeapGrowthIssue = true
                report.nonHeapIssueDetails.addAll(nonHeapGrowthDetails.map { detail -> "非堆增长: $detail" })
                logger.warn("检测到非堆增长: {}", nonHeapGrowthDetails)
            } else {
                // 预热完成后允许小幅抖动，避免把 JVM 正常的细粒度提交噪音升级为告警。
                logger.debug("预热完成后检测到小幅非堆波动: {}", nonHeapGrowthDetails)
            }
        }
        lastNonHeapUsedByPoolNameBytes = currentNonHeapUsedByPoolNameBytes
        // 在同一采样窗口内维护“预热完成 -> 长期增长”状态机，避免仅凭单次增长就误报长期风险。
        evaluateNonHeapTrend(
            report = report,
            metaspaceUsedBytes = metaspaceUsedBytes,
            codeCacheUsedBytes = codeCacheUsedBytes,
        )

        // 检查 CodeCache 总量
        val codeCacheRatio = report.codeCacheUsedMB.toDouble() / CODECACHE_LIMIT_MB
        if (codeCacheRatio > NON_HEAP_WARNING_THRESHOLD) {
            report.hasNonHeapIssue = true
            report.nonHeapIssueDetails.add("CodeCache: ${report.codeCacheUsedMB}MB / ${CODECACHE_LIMIT_MB}MB (${(codeCacheRatio * 100).toInt()}%)")
            logger.warn("CodeCache 使用率过高: ${report.codeCacheUsedMB}MB / ${CODECACHE_LIMIT_MB}MB")
        }

        if (!report.hasNonHeapIssue) {
            logger.debug("非堆内存正常 - Metaspace: ${report.metaspaceUsedMB}MB, CodeCache: ${report.codeCacheUsedMB}MB")
        }
    }

    /**
     * 比较前后两轮非堆分区占用，返回所有正向增长分区的明细。
     */
    private fun detectNonHeapGrowth(
        previousUsageByPoolNameBytes: Map<String, Long>,
        currentUsageByPoolNameBytes: Map<String, Long>,
    ): List<NonHeapGrowth> {
        if (previousUsageByPoolNameBytes.isEmpty() || currentUsageByPoolNameBytes.isEmpty()) {
            return emptyList()
        }

        return currentUsageByPoolNameBytes.entries
            .mapNotNull { (poolName, currentUsedBytes) ->
                val previousUsedBytes = previousUsageByPoolNameBytes[poolName] ?: 0L
                val deltaBytes = currentUsedBytes - previousUsedBytes
                if (deltaBytes >= NON_HEAP_GROWTH_LOG_MIN_BYTES) {
                    NonHeapGrowth(
                        poolName = poolName,
                        deltaBytes = deltaBytes,
                        previousBytes = previousUsedBytes,
                        currentBytes = currentUsedBytes,
                    )
                } else {
                    null
                }
            }
            .sortedByDescending { growth -> growth.deltaBytes }
    }

    /**
     * 维护非堆趋势窗口并识别“预热完成后长期增长且不回落”的风险模式。
     */
    private fun evaluateNonHeapTrend(
        report: MonitorReport,
        metaspaceUsedBytes: Long,
        codeCacheUsedBytes: Long,
    ) {
        val sample = NonHeapTrendSample(
            sampledAtMillis = System.currentTimeMillis(),
            metaspaceUsedBytes = metaspaceUsedBytes,
            codeCacheUsedBytes = codeCacheUsedBytes,
        )
        nonHeapTrendSamples.addLast(sample)
        while (nonHeapTrendSamples.size > NON_HEAP_TREND_MAX_SAMPLES) {
            nonHeapTrendSamples.removeFirst()
        }

        val samples = nonHeapTrendSamples.toList()
        val warmupWindow = samples.takeLast(minOf(NON_HEAP_WARMUP_WINDOW_SAMPLES, samples.size))
        val warmupMetaDriftBytes = if (warmupWindow.isEmpty()) 0L else {
            warmupWindow.maxOf { entry -> entry.metaspaceUsedBytes } - warmupWindow.minOf { entry -> entry.metaspaceUsedBytes }
        }
        val warmupCodeDriftBytes = if (warmupWindow.isEmpty()) 0L else {
            warmupWindow.maxOf { entry -> entry.codeCacheUsedBytes } - warmupWindow.minOf { entry -> entry.codeCacheUsedBytes }
        }
        val warmupMetaNetGrowthBytes = if (warmupWindow.size < 2) 0L else {
            (warmupWindow.last().metaspaceUsedBytes - warmupWindow.first().metaspaceUsedBytes).coerceAtLeast(0L)
        }
        val warmupCodeNetGrowthBytes = if (warmupWindow.size < 2) 0L else {
            (warmupWindow.last().codeCacheUsedBytes - warmupWindow.first().codeCacheUsedBytes).coerceAtLeast(0L)
        }

        if (!nonHeapWarmupCompleted &&
            samples.size >= NON_HEAP_WARMUP_WINDOW_SAMPLES &&
            warmupMetaDriftBytes <= NON_HEAP_WARMUP_STABLE_DRIFT_BYTES &&
            warmupCodeDriftBytes <= NON_HEAP_WARMUP_STABLE_DRIFT_BYTES &&
            warmupMetaNetGrowthBytes <= NON_HEAP_WARMUP_MAX_NET_GROWTH_BYTES &&
            warmupCodeNetGrowthBytes <= NON_HEAP_WARMUP_MAX_NET_GROWTH_BYTES
        ) {
            nonHeapWarmupCompleted = true
            nonHeapWarmupCompletedAtMillis = sample.sampledAtMillis
            logger.info(
                "非堆预热完成: window={} samples, metaspaceDrift={}KB, codeCacheDrift={}KB, metaspaceNet={}KB, codeCacheNet={}KB",
                NON_HEAP_WARMUP_WINDOW_SAMPLES,
                warmupMetaDriftBytes / 1024L,
                warmupCodeDriftBytes / 1024L,
                warmupMetaNetGrowthBytes / 1024L,
                warmupCodeNetGrowthBytes / 1024L,
            )
        }

        report.nonHeapWarmupCompleted = nonHeapWarmupCompleted
        report.nonHeapWarmupCompletedAtMillis = nonHeapWarmupCompletedAtMillis.takeIf { value -> value > 0L }

        if (!nonHeapWarmupCompleted) {
            val missingSamples = (NON_HEAP_WARMUP_WINDOW_SAMPLES - samples.size).coerceAtLeast(0)
            report.nonHeapTrendDetails = listOf(
                "预热状态: 进行中 (samples=${samples.size}/${NON_HEAP_WARMUP_WINDOW_SAMPLES}, missing=$missingSamples)",
                "预热窗口波动: Metaspace=${warmupMetaDriftBytes / 1024L}KB, CodeCache=${warmupCodeDriftBytes / 1024L}KB, 阈值=${NON_HEAP_WARMUP_STABLE_DRIFT_BYTES / 1024L}KB",
                "预热窗口净增长: Metaspace=${warmupMetaNetGrowthBytes / 1024L}KB, CodeCache=${warmupCodeNetGrowthBytes / 1024L}KB, 阈值=${NON_HEAP_WARMUP_MAX_NET_GROWTH_BYTES / 1024L}KB",
            )
            report.hasNonHeapLongGrowthIssue = false
            report.nonHeapLongGrowthDetails = emptyList()
            return
        }

        val trendWindowSize = minOf(NON_HEAP_LONG_GROWTH_WINDOW_SAMPLES, samples.size)
        val trendWindow = samples.takeLast(trendWindowSize)
        val metaspaceGrowthEvidence = detectSustainedNonHeapGrowth(
            samples = trendWindow,
            selector = { entry -> entry.metaspaceUsedBytes },
            minIncreaseBytes = NON_HEAP_LONG_GROWTH_MIN_INCREASE_METASPACE_BYTES,
            maxRetraceBytesThreshold = NON_HEAP_LONG_GROWTH_MAX_RETRACE_METASPACE_BYTES,
        )
        val codeCacheGrowthEvidence = detectSustainedNonHeapGrowth(
            samples = trendWindow,
            selector = { entry -> entry.codeCacheUsedBytes },
            minIncreaseBytes = NON_HEAP_LONG_GROWTH_MIN_INCREASE_CODECACHE_BYTES,
            maxRetraceBytesThreshold = NON_HEAP_LONG_GROWTH_MAX_RETRACE_CODECACHE_BYTES,
        )

        report.nonHeapTrendDetails = listOf(
            "预热状态: 已完成 (completedAt=${formatTimestamp(report.nonHeapWarmupCompletedAtMillis)})",
            "趋势窗口: ${trendWindowSize} samples, Metaspace阈值=${NON_HEAP_LONG_GROWTH_MIN_INCREASE_METASPACE_BYTES / 1024L}KB/回落容忍=${NON_HEAP_LONG_GROWTH_MAX_RETRACE_METASPACE_BYTES / 1024L}KB, CodeCache阈值=${NON_HEAP_LONG_GROWTH_MIN_INCREASE_CODECACHE_BYTES / 1024L}KB/回落容忍=${NON_HEAP_LONG_GROWTH_MAX_RETRACE_CODECACHE_BYTES / 1024L}KB",
        )

        val longGrowthDetails = buildList {
            metaspaceGrowthEvidence?.let { evidence ->
                add(
                    "Metaspace 长期增长: +${evidence.netIncreaseBytes / 1024L}KB, " +
                        "maxRetrace=${evidence.maxRetraceBytes / 1024L}KB, duration=${evidence.durationMillis / 1000L}s",
                )
            }
            codeCacheGrowthEvidence?.let { evidence ->
                add(
                    "CodeCache 长期增长: +${evidence.netIncreaseBytes / 1024L}KB, " +
                        "maxRetrace=${evidence.maxRetraceBytes / 1024L}KB, duration=${evidence.durationMillis / 1000L}s",
                )
            }
        }

        if (longGrowthDetails.isNotEmpty()) {
            report.hasNonHeapIssue = true
            report.hasNonHeapLongGrowthIssue = true
            report.nonHeapLongGrowthDetails = longGrowthDetails
            report.nonHeapIssueDetails.addAll(longGrowthDetails.map { detail -> "长期增长: $detail" })
            logger.warn("检测到非堆长期增长: {}", longGrowthDetails)
        } else {
            report.hasNonHeapLongGrowthIssue = false
            report.nonHeapLongGrowthDetails = emptyList()
        }
    }

    /**
     * 在固定趋势窗口内判定目标曲线是否满足“净增长达到阈值且无显著回落”。
     */
    private fun detectSustainedNonHeapGrowth(
        samples: List<NonHeapTrendSample>,
        selector: (NonHeapTrendSample) -> Long,
        minIncreaseBytes: Long,
        maxRetraceBytesThreshold: Long,
    ): NonHeapLongGrowthEvidence? {
        if (samples.size < 2) {
            return null
        }

        val values = samples.map(selector)
        val netIncreaseBytes = values.last() - values.first()
        if (netIncreaseBytes < minIncreaseBytes) {
            return null
        }

        var peakValue = values.first()
        var maxRetraceBytes = 0L
        values.forEach { currentValue ->
            if (currentValue >= peakValue) {
                peakValue = currentValue
            } else {
                val retraceBytes = peakValue - currentValue
                if (retraceBytes > maxRetraceBytes) {
                    maxRetraceBytes = retraceBytes
                }
            }
        }
        if (maxRetraceBytes > maxRetraceBytesThreshold) {
            return null
        }

        return NonHeapLongGrowthEvidence(
            netIncreaseBytes = netIncreaseBytes,
            maxRetraceBytes = maxRetraceBytes,
            durationMillis = (samples.last().sampledAtMillis - samples.first().sampledAtMillis).coerceAtLeast(0L),
        )
    }

    /**
     * 将毫秒时间戳格式化为守护日志可读时间，空值场景统一输出未完成状态。
     */
    private fun formatTimestamp(epochMillis: Long?): String {
        if (epochMillis == null || epochMillis <= 0L) {
            return "未完成"
        }
        return java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDateTime()
            .format(timeFormatter)
    }

    /**
     * 收集进程、线程、受管协程和 Skia 运行态快照。
     */
    private fun collectRuntimeResourceSnapshot(report: MonitorReport) {
        report.processMetrics = collectProcessMetrics()
        report.bufferPools = collectBufferPoolMetrics()
        report.threadMetrics = collectThreadMetrics()
        report.coroutineMetrics = collectManagedCoroutineMetrics()
        report.businessActivitySnapshots = BusinessLifecycleManager.runtimeActivitySnapshot()
        report.biliClientMetrics = BiliClient.runtimeSnapshot()
        report.platformObservability = collectPlatformRuntimeObservability()
        report.skiaStatus = SkiaManager.getStatus()
    }

    /**
     * 平台层 transport 观测需要在未初始化时也返回统一空快照，避免 guardian 因平台尚未启动而中断日志写入。
     */
    private fun collectPlatformRuntimeObservability(): PlatformObservabilitySnapshot {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            return PlatformObservabilitySnapshot.empty("platform adapter is not initialized")
        }
        // 平台连接器在停机阶段可能于检查后立刻释放，这里降级为空快照以避免 guardian 误判自身失败。
        return runCatching {
            BiliBiliBot.requireConnectorManager().runtimeObservability()
        }.getOrElse { error ->
            PlatformObservabilitySnapshot.empty("platform runtime observability unavailable: ${error.message}")
        }
    }

    /**
     * 采集当前 Java 进程的轻量级资源指标。
     */
    private fun collectProcessMetrics(): ProcessMetrics {
        val currentProcess = ProcessHandle.current()
        val procStatus = readProcStatusSnapshot()
        val smapsRollup = readSmapsRollupSnapshot()
        val osBean = runCatching {
            ManagementFactory.getPlatformMXBean(com.sun.management.OperatingSystemMXBean::class.java)
        }.getOrNull()

        val committedVirtualMB = osBean?.committedVirtualMemorySize
            ?.takeIf { it >= 0L }
            ?.div(1024L * 1024L)

        val source = if (procStatus != null) "VmRSS" else "OperatingSystemMXBean"
        val note = if (procStatus == null) {
            "Linux /proc/self/status 不可用，RSS 已降级为 committed virtual memory 观察"
        } else {
            null
        }

        return ProcessMetrics(
            pid = currentProcess.pid(),
            childProcessCount = currentProcess.children().count(),
            descendantProcessCount = currentProcess.descendants().count(),
            rssMB = procStatus?.vmRssKb?.div(1024L),
            vmSizeMB = procStatus?.vmSizeKb?.div(1024L),
            swapMB = procStatus?.vmSwapKb?.div(1024L),
            committedVirtualMB = committedVirtualMB,
            source = source,
            note = note,
            smapsRollup = smapsRollup,
        )
    }

    /**
     * 采集 JVM direct / mapped buffer 的占用情况。
     */
    private fun collectBufferPoolMetrics(): List<BufferPoolMetrics> {
        return ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java)
            .map { bean ->
                BufferPoolMetrics(
                    name = bean.name,
                    count = bean.count,
                    totalCapacityMB = bean.totalCapacity / 1024 / 1024,
                    memoryUsedMB = bean.memoryUsed / 1024 / 1024,
                )
            }
            .sortedByDescending { it.memoryUsedMB }
    }

    /**
     * 采集 JVM 线程数量和状态分布。
     */
    private fun collectThreadMetrics(): ThreadMetrics {
        val threadBean: ThreadMXBean = ManagementFactory.getThreadMXBean()
        val stateCounts = threadBean.getThreadInfo(threadBean.allThreadIds, 0)
            .filterNotNull()
            .groupingBy { it.threadState.name }
            .eachCount()
            .toSortedMap()

        return ThreadMetrics(
            liveThreadCount = threadBean.threadCount,
            daemonThreadCount = threadBean.daemonThreadCount,
            peakThreadCount = threadBean.peakThreadCount,
            stateCounts = stateCounts,
        )
    }

    /**
     * 统计项目内已登记的长生命周期 tasker / worker 数量。
     * JVM 无法精确给出单协程内存，因此这里只输出可证明的创建与存活数量。
     */
    private fun collectManagedCoroutineMetrics(): CoroutineMetrics {
        val taskerSnapshot = snapshotTaskers()
        val snapshots = taskerSnapshot.map { it.healthSnapshot() }
        val workerSnapshots = snapshots.flatMap { it.workerSnapshots }

        return CoroutineMetrics(
            taskerRegisteredCount = taskerSnapshot.size,
            taskerActiveCount = snapshots.count { it.active },
            unhealthyTaskerCount = snapshots.count { !it.healthy },
            workerRegisteredCount = workerSnapshots.size,
            workerActiveCount = workerSnapshots.count { it.active },
            workerFailedCount = workerSnapshots.count { it.restartExhausted || (!it.active && it.lastFailureMessage != null) },
            note = "仅统计 BiliTasker 体系内已登记的长生命周期协程",
        )
    }

    /**
     * 读取 Linux /proc/self/status 中的关键内存字段。
     */
    private fun readProcStatusSnapshot(): ProcStatusSnapshot? {
        val procStatusFile = File("/proc/self/status")
        if (!procStatusFile.exists()) {
            return null
        }

        return runCatching {
            val values = procStatusFile.readLines(StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) {
                        null
                    } else {
                        line.substring(0, index).trim() to line.substring(index + 1).trim()
                    }
                }
                .toMap()

            ProcStatusSnapshot(
                vmRssKb = parseProcStatusKilobytes(values, "VmRSS"),
                vmSizeKb = parseProcStatusKilobytes(values, "VmSize"),
                vmSwapKb = parseProcStatusKilobytes(values, "VmSwap"),
            )
        }.getOrNull()
    }

    /**
     * 从 /proc/self/status 提取形如 "12345 kB" 的数字值。
     */
    private fun parseProcStatusKilobytes(values: Map<String, String>, key: String): Long? {
        return values[key]
            ?.substringBefore(' ')
            ?.trim()
            ?.toLongOrNull()
    }

    /**
     * 读取 /proc/self/smaps_rollup 的匿名页、文件映射页与共享内存占用。
     */
    private fun readSmapsRollupSnapshot(): SmapsRollupSnapshot? {
        val smapsRollupFile = File("/proc/self/smaps_rollup")
        if (!smapsRollupFile.exists()) {
            return null
        }

        return runCatching {
            val values = smapsRollupFile.readLines(StandardCharsets.UTF_8)
                .mapNotNull { line ->
                    val index = line.indexOf(':')
                    if (index <= 0) {
                        null
                    } else {
                        line.substring(0, index).trim() to line.substring(index + 1).trim()
                    }
                }
                .toMap()

            SmapsRollupSnapshot(
                anonymousKb = parseProcStatusKilobytes(values, "Anonymous"),
                fileKb = parseProcStatusKilobytes(values, "File"),
                shmemKb = parseProcStatusKilobytes(values, "Shmem"),
            )
        }.getOrNull()
    }

    /**
     * 仅在异常或固定周期执行一次 jcmd VM.native_memory summary，并在不可用时自动降级。
     */
    private fun captureNativeMemorySummary(report: MonitorReport) {
        val now = System.currentTimeMillis()
        if (!shouldCaptureNativeSummary(report, now)) {
            report.nativeMemorySummary = lastNativeMemorySummary
            report.nativeSampleCaptured = false
            return
        }

        val previousSummary = lastNativeMemorySummary
        val summary = collectNativeMemorySummary()
        lastNativeMemorySampleAtMillis = now
        lastNativeMemorySummary = summary
        report.nativeMemorySummary = summary
        report.nativeSampleCaptured = true
        report.nativeSectionDeltas = buildNativeSectionDeltas(previousSummary, summary)
    }

    /**
     * 判定当前轮次是否需要触发重型 native memory 采样。
     */
    private fun shouldCaptureNativeSummary(report: MonitorReport, now: Long): Boolean {
        if (report.hasAnyIssue()) {
            return true
        }
        return now - lastNativeMemorySampleAtMillis >= NATIVE_MEMORY_SAMPLE_INTERVAL_MS
    }

    /**
     * 对连续两次 NMT 摘要计算分区增量，辅助排查是哪类 native 区在持续抬升。
     */
    private fun buildNativeSectionDeltas(previous: NativeMemorySummary?, current: NativeMemorySummary): List<NativeMemorySectionDelta> {
        if (previous?.status != "OK" || current.status != "OK") {
            return emptyList()
        }

        val previousSections = previous.sections.associateBy { section -> section.name }
        return current.sections
            .map { section ->
                val previousSection = previousSections[section.name]
                NativeMemorySectionDelta(
                    name = section.name,
                    deltaReservedMB = section.reservedMB - (previousSection?.reservedMB ?: 0L),
                    deltaCommittedMB = section.committedMB - (previousSection?.committedMB ?: 0L),
                    currentReservedMB = section.reservedMB,
                    currentCommittedMB = section.committedMB,
                )
            }
            .sortedByDescending { delta -> kotlin.math.abs(delta.deltaCommittedMB) }
    }

    /**
     * 把 native 分区增量与 owner 维度业务活动增量按同一采样窗口对齐，输出“疑似任务”线索。
     */
    private fun collectNativeTaskCorrelations(report: MonitorReport) {
        val nativeSummary = report.nativeMemorySummary ?: return
        if (!report.nativeSampleCaptured || nativeSummary.status != "OK") {
            return
        }

        val currentOwnerTotals = report.businessActivitySnapshots
            .associate { snapshot -> snapshot.owner to snapshot.totalRuns }
        val ownerSnapshots = report.businessActivitySnapshots
            .associateBy { snapshot -> snapshot.owner }
        val ownerNetworkPressure = collectOwnerNetworkPressure(report.biliClientMetrics)
        val positiveNativeGrowthMB = report.nativeSectionDeltas
            .filter { delta -> delta.deltaCommittedMB > 0L }
            .sumOf { delta -> delta.deltaCommittedMB }

        // 仅在 native committed 存在正向增长时给出任务相关性排名，避免把空窗口误导为任务异常。
        if (positiveNativeGrowthMB <= 0L) {
            report.nativeTaskCorrelations = emptyList()
            lastBusinessOwnerRunTotals = currentOwnerTotals
            return
        }

        val candidateOwners = buildSet {
            addAll(ownerSnapshots.keys)
            addAll(ownerNetworkPressure.keys)
            addAll(lastBusinessOwnerRunTotals.keys)
        }

        report.nativeTaskCorrelations = candidateOwners
            .mapNotNull { owner ->
                val ownerSnapshot = ownerSnapshots[owner]
                val totalRuns = currentOwnerTotals[owner] ?: 0L
                val previousRuns = lastBusinessOwnerRunTotals[owner] ?: 0L
                val operationDelta = (totalRuns - previousRuns).coerceAtLeast(0L)
                val activeSessions = ownerSnapshot?.activeSessions ?: 0
                val networkPressure = ownerNetworkPressure[owner] ?: OwnerNetworkPressure.EMPTY

                // 评分以业务活动增量为主，网络并发为辅，避免把共享基础设施噪音直接当成责任任务。
                val score = operationDelta * 10L +
                    activeSessions * 3L +
                    networkPressure.runningCalls.toLong() * 2L +
                    networkPressure.queuedCalls.toLong() +
                    networkPressure.createdSlots.toLong()
                if (score <= 0L) {
                    null
                } else {
                    NativeTaskCorrelation(
                        taskName = owner,
                        score = score,
                        operationDelta = operationDelta,
                        activeSessions = activeSessions,
                        clientRunningCalls = networkPressure.runningCalls,
                        clientQueuedCalls = networkPressure.queuedCalls,
                        evidence = buildString {
                            append("opsDelta=").append(operationDelta)
                            append(", activeSessions=").append(activeSessions)
                            append(", httpRunning=").append(networkPressure.runningCalls)
                            append(", httpQueued=").append(networkPressure.queuedCalls)
                            append(", nativeGrowth=").append(positiveNativeGrowthMB).append("MB")
                        },
                    )
                }
            }
            .sortedByDescending { correlation -> correlation.score }
            .take(NATIVE_TASK_CORRELATION_LIMIT)

        lastBusinessOwnerRunTotals = currentOwnerTotals
    }

    /**
     * 聚合 BiliClient ownerTag 的网络并发指标，供 native 相关性评分复用。
     */
    private fun collectOwnerNetworkPressure(
        snapshot: top.bilibili.client.BiliClientRuntimeSnapshot?,
    ): Map<String, OwnerNetworkPressure> {
        if (snapshot == null) {
            return emptyMap()
        }

        return snapshot.instances
            .groupBy { instance -> instance.ownerTag }
            .mapValues { (_, instances) ->
                var runningCalls = 0
                var queuedCalls = 0
                var createdSlots = 0
                instances.forEach { instance ->
                    createdSlots += instance.createdRetrySlotCount
                    instance.retrySlots
                        .filter { slot -> slot.created }
                        .forEach { slot ->
                            runningCalls += slot.runningCallsCount ?: 0
                            queuedCalls += slot.queuedCallsCount ?: 0
                        }
                }
                OwnerNetworkPressure(
                    runningCalls = runningCalls,
                    queuedCalls = queuedCalls,
                    createdSlots = createdSlots,
                )
            }
    }

    /**
     * 对共享 taskers 列表做外部同步快照，避免 guardian 在启动、恢复和停机期间直接迭代 synchronizedList。
     */
    private fun snapshotTaskers(): List<BiliTasker> {
        return synchronized(taskers) {
            taskers.toList()
        }
    }

    /**
     * 以 best-effort 方式执行 jcmd，提取 VM.native_memory summary 关键字段。
     */
    private fun collectNativeMemorySummary(): NativeMemorySummary {
        val jcmdCommand = detectJcmdCommand()
            ?: return NativeMemorySummary.unavailable("jcmd 不可用，已降级跳过 VM.native_memory summary")

        val pid = ProcessHandle.current().pid().toString()
        val process = runCatching {
            ProcessBuilder(jcmdCommand, pid, "VM.native_memory", "summary")
                .redirectErrorStream(true)
                .start()
        }.getOrElse { error ->
            return NativeMemorySummary.unavailable("jcmd 启动失败: ${error.message}")
        }

        try {
            // 先异步排空 stdout/stderr，再等待退出，避免 jcmd 因管道写满而卡死在 waitFor 之前。
            val outputDrain = drainProcessOutputAsync(process)
            val completed = runCatching {
                process.waitFor(NATIVE_MEMORY_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrElse { error ->
                outputDrain.awaitOutput(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS)
                return NativeMemorySummary.unavailable("jcmd 执行失败: ${error.message}")
            }

            if (!completed) {
                return NativeMemorySummary.unavailable("jcmd 执行超时，已降级跳过 VM.native_memory summary")
            }

            val output = outputDrain.awaitOutput(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS)
            if (outputDrain.isAlive()) {
                // 读取线程超时后先主动关闭输入流，避免 daemon 线程卡在 read() 导致累计线程残留。
                runCatching { process.inputStream.close() }
                outputDrain.awaitOutput(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS)
                return NativeMemorySummary.unavailable("jcmd 输出读取未在限定时间内完成，已降级跳过 VM.native_memory summary")
            }
            outputDrain.failureMessage()?.let { failure ->
                if (output.isBlank()) {
                    return NativeMemorySummary.unavailable("jcmd 输出读取失败: $failure")
                }
            }

            if (process.exitValue() != 0) {
                return NativeMemorySummary.unavailable("jcmd 返回非零退出码: ${process.exitValue()}")
            }
            if (output.contains("Native memory tracking is not enabled", ignoreCase = true)) {
                return NativeMemorySummary.unavailable("JVM 未开启 NMT，已降级跳过 VM.native_memory summary")
            }
            if (output.contains("Could not find", ignoreCase = true) || output.contains("AttachNotSupported", ignoreCase = true)) {
                return NativeMemorySummary.unavailable("jcmd attach 不可用，已降级跳过 VM.native_memory summary")
            }

            val totalMatch = Regex("""Total:\s*reserved=(\d+)KB,\s*committed=(\d+)KB""")
                .find(output)
            val sections = Regex("""(?m)^\s*-\s*([A-Za-z0-9 _\-/]+)\s*\(reserved=(\d+)KB,\s*committed=(\d+)KB\)""")
                .findAll(output)
                .map { match ->
                    NativeMemorySection(
                        name = match.groupValues[1].trim(),
                        reservedMB = match.groupValues[2].toLong() / 1024L,
                        committedMB = match.groupValues[3].toLong() / 1024L,
                    )
                }
                .sortedByDescending { it.committedMB }
                .toList()

            return NativeMemorySummary(
                status = "OK",
                reason = null,
                sampledAt = System.currentTimeMillis(),
                totalReservedMB = totalMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.div(1024L),
                totalCommittedMB = totalMatch?.groupValues?.getOrNull(2)?.toLongOrNull()?.div(1024L),
                sections = sections,
            )
        } finally {
            // jcmd 采样是短生命周期子进程，必须在所有路径统一释放三路流与进程句柄。
            closeProcessHandles(process)
        }
    }

    /**
     * 在等待子进程退出前持续排空输出，避免 Windows / Docker 管道缓冲区被 jcmd 写满后反向阻塞采样线程。
     */
    private fun drainProcessOutputAsync(process: Process): ProcessOutputDrain {
        return ProcessOutputDrain(process)
    }

    /**
     * 在 JDK / PATH 中查找 jcmd 可执行文件。
     */
    private fun detectJcmdCommand(): String? {
        val javaHome = System.getProperty("java.home").orEmpty()
        val candidates = listOf(
            File(javaHome, "bin/jcmd"),
            File(javaHome, "bin/jcmd.exe"),
            File(javaHome).parentFile?.let { File(it, "bin/jcmd") },
            File(javaHome).parentFile?.let { File(it, "bin/jcmd.exe") },
        ).filterNotNull()

        return candidates.firstOrNull { it.exists() && it.canExecute() }?.absolutePath
            ?: "jcmd".takeIf { probeJcmdInPath() }
    }

    /**
     * 探测 PATH 中的 jcmd 命令是否可执行，并确保探测子进程句柄完整回收。
     */
    private fun probeJcmdInPath(): Boolean {
        return runCatching {
            val probe = ProcessBuilder("jcmd", "-h")
                .redirectErrorStream(true)
                .start()
            try {
                val outputDrain = drainProcessOutputAsync(probe)
                val completed = probe.waitFor(NATIVE_MEMORY_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                if (!completed) {
                    probe.destroyForcibly()
                    probe.waitFor(NATIVE_MEMORY_DESTROY_WAIT_MS, TimeUnit.MILLISECONDS)
                }
                outputDrain.awaitOutput(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS)
                completed
            } finally {
                // 探测子进程也必须显式关闭全部流，避免 7x24 周期探测累计句柄。
                runCatching { probe.inputStream.close() }
                runCatching { probe.errorStream.close() }
                runCatching { probe.outputStream.close() }
                if (probe.isAlive) {
                    probe.destroyForcibly()
                    runCatching { probe.waitFor(NATIVE_MEMORY_DESTROY_WAIT_MS, TimeUnit.MILLISECONDS) }
                }
            }
        }.getOrDefault(false)
    }

    /**
     * 统一回收 jcmd 子进程的输入/输出流与进程句柄，避免守护进程长期运行时出现句柄滞留。
     */
    private fun closeProcessHandles(process: Process) {
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        runCatching { process.outputStream.close() }
        if (process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor(NATIVE_MEMORY_DESTROY_WAIT_MS, TimeUnit.MILLISECONDS) }
        }
    }

    /**
     * 收集内存占用最高的组件信息
     * 由于 JVM 无法直接获取每个对象的内存占用，这里估算主要组件的内存使用
     */
    private fun collectTopMemoryConsumers(): List<MemoryConsumer> {
        val consumers = mutableListOf<MemoryConsumer>()
        val taskerSnapshot = snapshotTaskers()

        // 1. 图片缓存
        try {
            val cacheStats = ImageCache.getCacheStats()
            consumers.add(MemoryConsumer(
                name = "ImageCache",
                estimatedMB = cacheStats.totalSizeBytes / 1024 / 1024,
                description = "${cacheStats.fileCount} 个文件"
            ))
        } catch (e: Exception) {
            logger.debug("获取 ImageCache 统计失败: ${e.message}")
        }

        // 2. 任务列表
        consumers.add(MemoryConsumer(
            name = "BiliTasker.taskers",
            estimatedMB = (taskerSnapshot.size * 1024L) / 1024 / 1024,  // 估算每个任务约1KB
            description = "${taskerSnapshot.size} 个任务"
        ))

        // 3. Channel 缓冲区估算
        consumers.add(MemoryConsumer(
            name = "Channels",
            estimatedMB = estimateChannelMemory(),
            description = "dynamicChannel + liveChannel + messageChannel"
        ))

        // 4. JVM 堆内存
        val runtime = Runtime.getRuntime()
        val heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024
        consumers.add(MemoryConsumer(
            name = "JVM Heap Used",
            estimatedMB = heapUsed,
            description = "已使用堆内存"
        ))

        // 5. JVM 堆内存总量
        val heapTotal = runtime.totalMemory() / 1024 / 1024
        consumers.add(MemoryConsumer(
            name = "JVM Heap Total",
            estimatedMB = heapTotal,
            description = "已分配堆内存"
        ))

        // 按内存占用排序，取前5个
        return consumers.sortedByDescending { it.estimatedMB }.take(5)
    }

    /**
     * 估算 Channel 内存占用
     */
    private fun estimateChannelMemory(): Long {
        // 用保守估算值即可满足监控排序目的，避免为精确统计引入高成本运行时探测。
        // Channel 容量: dynamicChannel(20) + liveChannel(20) + messageChannel(20) = 60
        // 假设每个消息平均 2KB
        return (60 * 2 * 1024L) / 1024 / 1024
    }

    /**
     * 检查连接状态
     */
    private fun checkConnectionStatus(report: MonitorReport) {
        if (!BiliBiliBot.isPlatformAdapterInitialized()) {
            report.hasConnectionIssue = false
            return
        }

        // 连接管理器在停机窗口内可能被并发释放，守护进程应降级为不可用而不是抛异常退出本轮巡检。
        val runtimeStatus = runCatching {
            BiliBiliBot.requireConnectorManager().runtimeStatus()
        }.getOrElse { error ->
            logger.debug("获取平台连接状态失败，已降级跳过本轮连接检查: ${error.message}")
            report.hasConnectionIssue = false
            report.connectionStatus = "UNKNOWN"
            return
        }
        val isConnected = runtimeStatus.connected
        val reconnectAttempts = runtimeStatus.reconnectAttempts

        if (!isConnected) {
            disconnectedDuration += interval

            report.hasConnectionIssue = true
            report.connectionStatus = "DISCONNECTED"
            report.disconnectedSeconds = disconnectedDuration
            report.reconnectAttempts = reconnectAttempts

            logger.warn("WebSocket 连接已断开 ${disconnectedDuration}秒，重连次数: $reconnectAttempts")

            // 如果断开超过2分钟，记录为严重问题
            if (disconnectedDuration >= 120) {
                report.connectionIssueLevel = "CRITICAL"
            } else {
                report.connectionIssueLevel = "WARNING"
            }
        } else {
            // 连接恢复
            if (!lastConnectionStatus) {
                logger.info("WebSocket 连接已恢复")
            }
            disconnectedDuration = 0
            report.hasConnectionIssue = false
            report.connectionStatus = "CONNECTED"
        }

        lastConnectionStatus = isConnected
    }

    /**
     * 清理僵尸任务
     */
    private fun cleanDeadTaskers(report: MonitorReport) {
        val deadTaskers = snapshotTaskers().filter { it != this && !it.isActive }

        if (deadTaskers.isNotEmpty()) {
            // 从列表中移除僵尸任务
            val removedCount = synchronized(taskers) {
                val beforeCount = taskers.size
                taskers.removeAll(deadTaskers.toSet())
                beforeCount - taskers.size
            }

            report.hasZombieTaskers = true
            report.zombieTaskersCleaned = removedCount
            report.zombieTaskerNames = deadTaskers.mapNotNull { it::class.simpleName }

            logger.info("已清理 $removedCount 个僵尸任务引用: ${report.zombieTaskerNames}")
        } else {
            report.hasZombieTaskers = false
        }
    }

    /**
     * ✅ P3修复: 检查 Channel 背压状态
     * 使用 trySend 检测 Channel 是否已满
     */
    private fun checkChannelBackpressure(report: MonitorReport) {
        val fullChannels = mutableListOf<String>()

        // 检查 BiliBiliBot 的 Channel
        try {
            if (BiliBiliBot.isPlatformAdapterInitialized()) {
                val runtimeStatus = BiliBiliBot.requireConnectorManager().runtimeStatus()
                if (runtimeStatus.outboundPressureActive) {
                    fullChannels.add("platform.outbound (dropped=${runtimeStatus.outboundDroppedEvents})")
                    logger.warn("平台出站背压已触发，可能存在消息积压")
                }
                if (runtimeStatus.inboundPressureActive) {
                    fullChannels.add("platform.inbound (dropped=${runtimeStatus.inboundDroppedEvents})")
                    logger.warn("平台入站背压已触发，存在事件丢弃风险")
                }
            }
        } catch (e: Exception) {
            logger.debug("检查 Channel 背压时出错: ${e.message}")
        }

        if (fullChannels.isNotEmpty()) {
            report.hasBackpressure = true
            report.backpressureChannels = fullChannels
            logger.warn("检测到 Channel 背压: ${fullChannels.joinToString(", ")}")
        } else {
            report.hasBackpressure = false
            logger.debug("Channel 背压检查正常")
        }
    }

    /**
     * 基于 VmRSS 连续超阈窗口执行软限制告警，并在窗口满足后登记条件重启请求。
     */
    private fun evaluateRssSoftLimit(report: MonitorReport) {
        val processMetrics = report.processMetrics ?: run {
            rssAboveSoftLimitSinceMillis = 0L
            return
        }
        val rssMB = processMetrics.rssMB ?: run {
            rssAboveSoftLimitSinceMillis = 0L
            return
        }

        // 同步输出 VmRSS 与 NMT committed 的差值，定位 JVM 外驻留增长。
        report.nmtCommittedMB = report.nativeMemorySummary
            ?.takeIf { native -> native.status == "OK" }
            ?.totalCommittedMB
        report.rssMinusNmtMB = report.nmtCommittedMB
            ?.let { committedMB -> rssMB - committedMB }
        // 把 VmRSS-NMT committed 的正向差值记录为“未归类 native 区”估算，便于长期趋势对比。
        report.unattributedNativeMB = report.rssMinusNmtMB?.coerceAtLeast(0L)

        report.rssSoftLimitMB = RSS_SOFT_LIMIT_MB
        report.rssSoftLimitHoldSeconds = RSS_SOFT_LIMIT_HOLD_MS / 1000L

        if (rssMB < RSS_SOFT_LIMIT_MB) {
            rssAboveSoftLimitSinceMillis = 0L
            report.rssSoftLimitActive = false
            report.rssSoftLimitDurationSeconds = 0L
            report.rssSoftLimitIssueLevel = "NORMAL"
            return
        }

        if (rssAboveSoftLimitSinceMillis == 0L) {
            rssAboveSoftLimitSinceMillis = System.currentTimeMillis()
        }
        val continuousHighDurationMs = System.currentTimeMillis() - rssAboveSoftLimitSinceMillis
        report.rssSoftLimitActive = true
        report.rssSoftLimitDurationSeconds = continuousHighDurationMs / 1000L

        if (continuousHighDurationMs >= RSS_SOFT_LIMIT_HOLD_MS) {
            report.hasRssSoftLimitIssue = true
            report.rssSoftLimitIssueLevel = "RESTART_PENDING"
            report.rssSoftRestartPending = true
            report.rssSoftRestartReason =
                "VmRSS=$rssMB MB 连续 ${(continuousHighDurationMs / 1000L) / 60L} 分钟超过阈值 ${RSS_SOFT_LIMIT_MB} MB"
            return
        }

        if (continuousHighDurationMs >= RSS_SOFT_LIMIT_WARN_AFTER_MS) {
            report.hasRssSoftLimitIssue = true
            report.rssSoftLimitIssueLevel = "WARNING"
        } else {
            report.rssSoftLimitIssueLevel = "OBSERVING"
        }
    }

    /**
     * 在守护日志写盘后执行条件重启，确保触发现场先进入 Daemon 日志。
     */
    private fun executeRssSoftLimitRestart(report: MonitorReport) {
        if (!report.rssSoftRestartPending) {
            return
        }

        val reason = report.rssSoftRestartReason ?: "VmRSS 连续超阈，触发条件重启"
        logger.error("RSS 软限制触发条件重启: {}", reason)

        // 先执行常规停机流程释放资源，再通过退出码交给容器重启策略拉起新实例。
        runCatching { BiliBiliBot.stop("rss-soft-limit") }
            .onFailure { error -> logger.error("RSS 软限制停机流程失败: ${error.message}", error) }
        exitProcess(RSS_SOFT_LIMIT_RESTART_EXIT_CODE)
    }

    /**
     * 正常清理
     */
    private fun normalCleanup() {
        logger.info("执行正常清理...")
        try {
            ImageCache.cleanExpiredCache()
            logger.info("图片缓存清理完成")
        } catch (e: Exception) {
            logger.error("清理失败: ${e.message}", e)
        }
    }

    /**
     * 紧急清理
     */
    private fun emergencyCleanup() {
        logger.warn("执行紧急清理...")
        try {
            // 1. 清理所有缓存
            ImageCache.cleanCache()

            // 2. 建议 GC
            System.gc()

            logger.info("紧急清理完成")
        } catch (e: Exception) {
            logger.error("紧急清理失败: ${e.message}", e)
        }
    }

    /**
     * 写入监控日志
     * 只在有异常时写入，或每10分钟写入一次正常状态
     */
    private fun writeMonitorLog(report: MonitorReport) {
        val hasAnyIssue = report.hasAnyIssue()
        val currentMinute = System.currentTimeMillis() / 1000 / 60

        // 仅在分钟边界首次命中时写入，避免 30 秒巡检间隔导致同一分钟重复写入“正常”日志。
        val shouldWriteNormalLog = !hasAnyIssue && (currentMinute % 10 == 0L) && lastNormalLogMinute != currentMinute

        if (!hasAnyIssue && !shouldWriteNormalLog) {
            return
        }

        if (shouldWriteNormalLog) {
            lastNormalLogMinute = currentMinute
        }

        // 获取或创建今日日志文件
        val logFile = getOrCreateLogFile()

        try {
            // 使用 PrintWriter 写入，确保资源正确关闭
            PrintWriter(OutputStreamWriter(FileOutputStream(logFile, true), StandardCharsets.UTF_8)).use { writer ->
                val timestamp = LocalDateTime.now().format(timeFormatter)

                writer.println("=" .repeat(60))
                writer.println("[$timestamp] 守护进程监控报告")
                writer.println("=" .repeat(60))

                // 1. 任务健康状态
                writer.println("[任务健康状态]")
                if (report.hasTaskerIssue) {
                    writer.println("  异常任务: ${report.deadTaskerNames.joinToString(", ")}")
                } else {
                    writer.println("  检查无异常")
                }

                // 2. 内存使用（始终写入前5个占用最高的组件）
                writer.println("[内存使用] ${report.memoryUsedMB}MB / ${report.memoryMaxMB}MB (${report.memoryUsagePercent}%)")
                if (report.hasMemoryIssue) {
                    writer.println("  状态: ${report.memoryIssueLevel}")
                }
                writer.println("  Top 5 内存占用:")
                report.topMemoryConsumers.forEachIndexed { index, consumer ->
                    writer.println("    ${index + 1}. ${consumer.name}: ${consumer.estimatedMB}MB (${consumer.description})")
                }

                // 2.5 非堆内存 (Metaspace, CodeCache)
                writer.println("[非堆内存] Metaspace: ${report.metaspaceUsedMB}MB/${METASPACE_LIMIT_MB}MB, CodeCache: ${report.codeCacheUsedMB}MB/${CODECACHE_LIMIT_MB}MB")
                if (report.hasNonHeapIssue) {
                    writer.println("  告警:")
                    report.nonHeapIssueDetails.forEach { detail ->
                        writer.println("    - $detail")
                    }
                }
                if (report.hasNonHeapGrowthIssue && report.nonHeapGrowthDetails.isNotEmpty()) {
                    writer.println("  增长:")
                    report.nonHeapGrowthDetails.forEach { detail ->
                        writer.println("    - $detail")
                    }
                }
                if (report.nonHeapBreakdown.isNotEmpty()) {
                    writer.println("[非堆细分]")
                    report.nonHeapBreakdown.forEach { partition ->
                        writer.println(
                            "  ${partition.name}: used=${partition.usedMB}MB, " +
                                "max=${partition.maxMB?.let { "${it}MB" } ?: "未设置"}, " +
                                "usage=${partition.usagePercent?.let { "${it}%" } ?: "未知"}",
                        )
                    }
                }
                if (report.nonHeapTrendDetails.isNotEmpty() || report.nonHeapLongGrowthDetails.isNotEmpty()) {
                    writer.println("[非堆趋势]")
                    report.nonHeapTrendDetails.forEach { detail ->
                        writer.println("  $detail")
                    }
                    if (report.hasNonHeapLongGrowthIssue && report.nonHeapLongGrowthDetails.isNotEmpty()) {
                        writer.println("  长期增长告警:")
                        report.nonHeapLongGrowthDetails.forEach { detail ->
                            writer.println("    - $detail")
                        }
                    }
                }

                // 2.8 进程级资源快照
                report.processMetrics?.let { process ->
                    writer.println("[进程资源]")
                    writer.println("  PID: ${process.pid}, 子进程: ${process.childProcessCount}, 后代进程: ${process.descendantProcessCount}")
                    writer.println(
                        "  来源: ${process.source}, VmRSS: ${process.rssMB?.let { "${it}MB" } ?: "不可用"}, " +
                            "VmSize: ${process.vmSizeMB?.let { "${it}MB" } ?: "不可用"}, " +
                            "VmSwap: ${process.swapMB?.let { "${it}MB" } ?: "不可用"}, " +
                            "CommittedVirtual: ${process.committedVirtualMB?.let { "${it}MB" } ?: "不可用"}"
                    )
                    process.note?.let { writer.println("  说明: $it") }
                    process.smapsRollup?.let { smaps ->
                        writer.println(
                            "  smaps_rollup: Anonymous=${smaps.anonymousKb?.div(1024L)?.let { "${it}MB" } ?: "不可用"}, " +
                                "File=${smaps.fileKb?.div(1024L)?.let { "${it}MB" } ?: "不可用"}, " +
                                "Shmem=${smaps.shmemKb?.div(1024L)?.let { "${it}MB" } ?: "不可用"}"
                        )
                    }
                }

                // 2.9 Direct / mapped buffer 快照
                if (report.bufferPools.isNotEmpty()) {
                    writer.println("[BufferPool]")
                    report.bufferPools.forEach { pool ->
                        writer.println(
                            "  ${pool.name}: count=${pool.count}, used=${pool.memoryUsedMB}MB, capacity=${pool.totalCapacityMB}MB"
                        )
                    }
                }

                // 2.10 线程状态分布
                report.threadMetrics?.let { threads ->
                    writer.println("[线程状态]")
                    writer.println(
                        "  Live: ${threads.liveThreadCount}, Daemon: ${threads.daemonThreadCount}, Peak: ${threads.peakThreadCount}"
                    )
                    if (threads.stateCounts.isNotEmpty()) {
                        writer.println("  States: ${threads.stateCounts.entries.joinToString { "${it.key}=${it.value}" }}")
                    }
                }

                // 2.11 受管协程/任务快照
                report.coroutineMetrics?.let { coroutines ->
                    writer.println("[任务与协程]")
                    writer.println(
                        "  Tasker: registered=${coroutines.taskerRegisteredCount}, active=${coroutines.taskerActiveCount}, unhealthy=${coroutines.unhealthyTaskerCount}"
                    )
                    writer.println(
                        "  Worker: registered=${coroutines.workerRegisteredCount}, active=${coroutines.workerActiveCount}, failed=${coroutines.workerFailedCount}"
                    )
                    writer.println("  说明: ${coroutines.note}")
                }

                // 2.12 owner 维度业务活动快照
                if (report.businessActivitySnapshots.isNotEmpty()) {
                    writer.println("[业务生命周期活动]")
                    report.businessActivitySnapshots.forEach { owner ->
                        val topOperations = owner.operations.entries
                            .sortedByDescending { entry -> entry.value }
                            .take(3)
                            .joinToString { entry -> "${entry.key}=${entry.value}" }
                        writer.println(
                            "  ${owner.owner}: active=${owner.activeSessions}, runs=${owner.totalRuns}, ops=${if (topOperations.isBlank()) "无" else topOperations}"
                        )
                    }
                }

                // 2.13 BiliClient / OkHttp 运行态
                report.biliClientMetrics?.let { clients ->
                    writer.println("[BiliClient / OkHttp]")
                    writer.println(
                        "  Instances: totalCreated=${clients.totalCreatedCount}, active=${clients.activeInstanceCount}"
                    )
                    writer.println(
                        "  RetrySlots: created=${clients.createdRetrySlotCount}, capacity=${clients.retrySlotCapacity}"
                    )
                    clients.instances.forEach { instance ->
                        writer.println(
                            "  client#${instance.instanceId}(${instance.ownerTag}): " +
                                "createdSlots=${instance.createdRetrySlotCount}/${instance.retrySlotCapacity}"
                        )
                        instance.retrySlots
                            .filter { slot -> slot.created }
                            .forEach { slot ->
                                writer.println(
                                    "    slot${slot.slotIndex}: " +
                                        "connections=${slot.connectionCount}, " +
                                        "idle=${slot.idleConnectionCount}, " +
                                        "queued=${slot.queuedCallsCount}, " +
                                        "running=${slot.runningCallsCount}"
                                )
                            }
                    }
                }

                // 2.14 平台 transport HttpClient / OkHttp 运行态
                writer.println("[Platform HttpClient / OkHttp]")
                report.platformObservability.note?.let { writer.println("  说明: $it") }
                if (report.platformObservability.clients.isEmpty()) {
                    writer.println("  snapshots=0")
                } else {
                    report.platformObservability.clients.forEach { snapshot ->
                        writer.println(
                            "  ${snapshot.adapterName}/${snapshot.transportName}: " +
                                "connections=${snapshot.connectionCount?.toString() ?: "未知"}, " +
                                "idle=${snapshot.idleConnectionCount?.toString() ?: "未知"}, " +
                                "queued=${snapshot.queuedCallsCount?.toString() ?: "未知"}, " +
                                "running=${snapshot.runningCallsCount?.toString() ?: "未知"}, " +
                                "webSocketSessionActive=${snapshot.webSocketSessionActive}"
                        )
                        snapshot.note?.let { writer.println("    note=$it") }
                    }
                }

                // 2.15 Skia 运行态
                report.skiaStatus?.let { skia ->
                    writer.println("[Skia 状态]")
                    writer.println(
                        "  mode=${skia.mode}, memoryUsage=${(skia.memoryUsage * 100).toInt()}%, " +
                            "drawings=${skia.totalDrawingCount}, cleanups=${skia.totalCleanupCount}, uptimeMs=${skia.uptimeMs}"
                    )
                    writer.println(
                        "  queue: pending=${skia.queueStatus.pendingCount}, active=${skia.queueStatus.activeCount}, full=${skia.queueStatus.isFull}"
                    )
                }

                // 2.16 可降级的 Native Memory Tracking 摘要（全量分区）
                report.nativeMemorySummary?.let { native ->
                    writer.println("[Native Memory Summary]")
                    writer.println("  状态: ${native.status}")
                    native.reason?.let { writer.println("  说明: $it") }
                    if (native.status == "OK") {
                        writer.println(
                            "  Total reserved=${native.totalReservedMB?.let { "${it}MB" } ?: "未知"}, " +
                                "committed=${native.totalCommittedMB?.let { "${it}MB" } ?: "未知"}, sections=${native.sections.size}"
                        )
                        native.sections.forEach { section ->
                            writer.println("    - ${section.name}: reserved=${section.reservedMB}MB, committed=${section.committedMB}MB")
                        }
                    }
                }
                if (report.nativeSectionDeltas.isNotEmpty()) {
                    writer.println("[Native 增量]")
                    report.nativeSectionDeltas.forEach { delta ->
                        writer.println(
                            "  ${delta.name}: deltaCommitted=${delta.deltaCommittedMB}MB, " +
                                "deltaReserved=${delta.deltaReservedMB}MB, " +
                                "currentCommitted=${delta.currentCommittedMB}MB"
                        )
                    }
                }
                if (report.nativeSampleCaptured) {
                    writer.println("[Native 任务相关性]")
                    if (report.nativeTaskCorrelations.isEmpty()) {
                        writer.println("  无显著相关任务（本轮 native committed 未出现正向增长或任务活动不足）")
                    } else {
                        report.nativeTaskCorrelations.forEachIndexed { index, correlation ->
                            writer.println(
                                "  ${index + 1}. ${correlation.taskName}: score=${correlation.score}, " +
                                    "opsDelta=${correlation.operationDelta}, active=${correlation.activeSessions}, " +
                                    "running=${correlation.clientRunningCalls}, queued=${correlation.clientQueuedCalls}"
                            )
                            writer.println("     evidence=${correlation.evidence}")
                        }
                    }
                }

                writer.println("[Native 未归类估算]")
                writer.println(
                    "  VmRSS=${report.processMetrics?.rssMB?.let { "${it}MB" } ?: "不可用"}, " +
                        "NMT_committed=${report.nmtCommittedMB?.let { "${it}MB" } ?: "不可用"}, " +
                        "RssMinusNmt=${report.rssMinusNmtMB?.let { "${it}MB" } ?: "不可用"}"
                )
                writer.println(
                    "  unattributedNative=${report.unattributedNativeMB?.let { "${it}MB" } ?: "不可用"} " +
                        "(max(VmRSS - NMT_committed, 0))"
                )

                writer.println("[RSS 软限制]")
                writer.println(
                    "  threshold=${report.rssSoftLimitMB}MB, hold=${report.rssSoftLimitHoldSeconds}s, " +
                        "active=${report.rssSoftLimitActive}, duration=${report.rssSoftLimitDurationSeconds}s, " +
                        "level=${report.rssSoftLimitIssueLevel}"
                )
                writer.println(
                    "  RssMinusNmt=${report.rssMinusNmtMB?.let { "${it}MB" } ?: "不可用"} " +
                        "(VmRSS - NMT_committed)"
                )
                report.rssSoftRestartReason?.let { reason ->
                    writer.println("  action=restart, reason=$reason")
                }

                // 3. 连接状态（只在异常时写入）
                if (report.hasConnectionIssue) {
                    writer.println("[连接状态] 异常")
                    writer.println("  状态: ${report.connectionStatus}")
                    writer.println("  断开时长: ${report.disconnectedSeconds}秒")
                    writer.println("  重连次数: ${report.reconnectAttempts}")
                    writer.println("  严重级别: ${report.connectionIssueLevel}")
                }

                // 4. 僵尸任务（只在有清理时写入）
                if (report.hasZombieTaskers) {
                    writer.println("[僵尸任务清理]")
                    writer.println("  清理数量: ${report.zombieTaskersCleaned}")
                    writer.println("  任务名称: ${report.zombieTaskerNames.joinToString(", ")}")
                }

                // 5. ✅ P3修复: Channel 背压（只在有背压时写入）
                if (report.hasBackpressure) {
                    writer.println("[Channel 背压告警]")
                    writer.println("  受影响的 Channel:")
                    report.backpressureChannels.forEach { channel ->
                        writer.println("    - $channel")
                    }
                    writer.println("  建议: 检查消息消费速度或增加 Channel 容量")
                }

                writer.println()
            }

            logger.debug("监控日志已写入: ${logFile.name}")

        } catch (e: Exception) {
            logger.error("写入监控日志失败: ${e.message}", e)
        }
    }

    /**
     * 获取或创建今日日志文件
     */
    private fun getOrCreateLogFile(): File {
        val today = LocalDate.now().format(dateFormatter)
        val logFile = File(logDir, "Daemon_$today.log")

        if (!logFile.exists()) {
            try {
                logFile.createNewFile()
                // 首次创建时写入文件头，便于按天轮转后直接人工排查。
                // 写入文件头
                PrintWriter(OutputStreamWriter(FileOutputStream(logFile), StandardCharsets.UTF_8)).use { writer ->
                    writer.println("=" .repeat(60))
                    writer.println("BiliBili 动态推送 Bot - 守护进程监控日志")
                    writer.println("日期: $today")
                    writer.println("=" .repeat(60))
                    writer.println()
                }
                logger.info("创建新的监控日志文件: ${logFile.name}")
            } catch (e: Exception) {
                logger.error("创建日志文件失败: ${e.message}", e)
            }
        }

        return logFile
    }

    /**
     * 监控报告数据类
     */
    private data class MonitorReport(
        // 任务健康
        var hasTaskerIssue: Boolean = false,
        var deadTaskerNames: List<String> = emptyList(),

        // 内存使用
        var hasMemoryIssue: Boolean = false,
        var memoryIssueLevel: String = "",
        var memoryUsedMB: Long = 0,
        var memoryMaxMB: Long = 0,
        var memoryUsagePercent: Int = 0,
        var topMemoryConsumers: List<MemoryConsumer> = emptyList(),

        // 非堆内存 (Metaspace, CodeCache)
        var hasNonHeapIssue: Boolean = false,
        var hasNonHeapGrowthIssue: Boolean = false,
        var hasNonHeapLongGrowthIssue: Boolean = false,
        var metaspaceUsedMB: Long = 0,
        var codeCacheUsedMB: Long = 0,
        var nonHeapIssueDetails: MutableList<String> = mutableListOf(),
        var nonHeapGrowthDetails: List<String> = emptyList(),
        var nonHeapLongGrowthDetails: List<String> = emptyList(),
        var nonHeapWarmupCompleted: Boolean = false,
        var nonHeapWarmupCompletedAtMillis: Long? = null,
        var nonHeapTrendDetails: List<String> = emptyList(),
        var nonHeapBreakdown: List<NonHeapPartitionUsage> = emptyList(),

        // 进程 / 线程 / 协程 / Skia 轻量快照
        var processMetrics: ProcessMetrics? = null,
        var bufferPools: List<BufferPoolMetrics> = emptyList(),
        var threadMetrics: ThreadMetrics? = null,
        var coroutineMetrics: CoroutineMetrics? = null,
        var businessActivitySnapshots: List<BusinessOwnerActivitySnapshot> = emptyList(),
        var biliClientMetrics: top.bilibili.client.BiliClientRuntimeSnapshot? = null,
        var platformObservability: PlatformObservabilitySnapshot = PlatformObservabilitySnapshot.empty("platform adapter is not initialized"),
        var skiaStatus: top.bilibili.skia.SkiaManagerStatus? = null,
        var nativeMemorySummary: NativeMemorySummary? = null,
        var nativeSampleCaptured: Boolean = false,
        var nativeSectionDeltas: List<NativeMemorySectionDelta> = emptyList(),
        var nativeTaskCorrelations: List<NativeTaskCorrelation> = emptyList(),
        var nmtCommittedMB: Long? = null,
        var rssMinusNmtMB: Long? = null,
        var unattributedNativeMB: Long? = null,
        var hasRssSoftLimitIssue: Boolean = false,
        var rssSoftLimitMB: Long = RSS_SOFT_LIMIT_MB,
        var rssSoftLimitHoldSeconds: Long = RSS_SOFT_LIMIT_HOLD_MS / 1000L,
        var rssSoftLimitActive: Boolean = false,
        var rssSoftLimitDurationSeconds: Long = 0L,
        var rssSoftLimitIssueLevel: String = "NORMAL",
        var rssSoftRestartPending: Boolean = false,
        var rssSoftRestartReason: String? = null,

        // 连接状态
        var hasConnectionIssue: Boolean = false,
        var connectionStatus: String = "UNKNOWN",
        var connectionIssueLevel: String = "",
        var disconnectedSeconds: Int = 0,
        var reconnectAttempts: Int = 0,

        // 僵尸任务
        var hasZombieTaskers: Boolean = false,
        var zombieTaskersCleaned: Int = 0,
        var zombieTaskerNames: List<String> = emptyList(),

        // ✅ P3修复: Channel 背压监控
        var hasBackpressure: Boolean = false,
        var backpressureChannels: List<String> = emptyList()
    ) {
        /**
         * 统一判断当前报告是否已经触发异常态。
         */
        fun hasAnyIssue(): Boolean {
            return hasTaskerIssue ||
                hasMemoryIssue ||
                hasNonHeapIssue ||
                hasNonHeapGrowthIssue ||
                hasNonHeapLongGrowthIssue ||
                hasRssSoftLimitIssue ||
                hasConnectionIssue ||
                hasZombieTaskers ||
                hasBackpressure
        }
    }

    /**
     * /proc/self/status 的内存快照。
     */
    private data class ProcStatusSnapshot(
        val vmRssKb: Long?,
        val vmSizeKb: Long?,
        val vmSwapKb: Long?,
    )

    /**
     * Java 进程级资源快照。
     */
    private data class ProcessMetrics(
        val pid: Long,
        val childProcessCount: Long,
        val descendantProcessCount: Long,
        val rssMB: Long?,
        val vmSizeMB: Long?,
        val swapMB: Long?,
        val committedVirtualMB: Long?,
        val source: String,
        val note: String?,
        val smapsRollup: SmapsRollupSnapshot?,
    )

    /**
     * /proc/self/smaps_rollup 的关键内存分项快照。
     */
    private data class SmapsRollupSnapshot(
        val anonymousKb: Long?,
        val fileKb: Long?,
        val shmemKb: Long?,
    )

    /**
     * JVM BufferPool 指标。
     */
    private data class BufferPoolMetrics(
        val name: String,
        val count: Long,
        val totalCapacityMB: Long,
        val memoryUsedMB: Long,
    )

    /**
     * 非堆内存分区快照，用于记录 Metaspace/CodeHeap 各子分区占用，辅助定位单分区抬升。
     */
    private data class NonHeapPartitionUsage(
        val name: String,
        val usedMB: Long,
        val maxMB: Long?,
        val usagePercent: Int?,
    )

    /**
     * 单个非堆分区在相邻巡检窗口中的增长快照（按字节统计，避免低于 1MB 的增长被舍入丢失）。
     */
    private data class NonHeapGrowth(
        val poolName: String,
        val deltaBytes: Long,
        val previousBytes: Long,
        val currentBytes: Long,
    )

    /**
     * 单轮守护采样的非堆总量快照，用于判断预热完成与长期增长趋势。
     */
    private data class NonHeapTrendSample(
        val sampledAtMillis: Long,
        val metaspaceUsedBytes: Long,
        val codeCacheUsedBytes: Long,
    )

    /**
     * 长期增长判定结果，记录净增长、最大回落和覆盖时长，便于在日志中复核判定依据。
     */
    private data class NonHeapLongGrowthEvidence(
        val netIncreaseBytes: Long,
        val maxRetraceBytes: Long,
        val durationMillis: Long,
    )

    /**
     * JVM 线程统计快照。
     */
    private data class ThreadMetrics(
        val liveThreadCount: Int,
        val daemonThreadCount: Int,
        val peakThreadCount: Int,
        val stateCounts: Map<String, Int>,
    )

    /**
     * 受管长生命周期协程统计快照。
     */
    private data class CoroutineMetrics(
        val taskerRegisteredCount: Int,
        val taskerActiveCount: Int,
        val unhealthyTaskerCount: Int,
        val workerRegisteredCount: Int,
        val workerActiveCount: Int,
        val workerFailedCount: Int,
        val note: String,
    )

    /**
     * jcmd 输出异步排空器。
     * 该结构确保 guardian 在等待子进程退出前就开始消费输出，避免高输出量场景卡住采样线程。
     */
    private class ProcessOutputDrain(process: Process) {
        private val output = StringBuilder()

        @Volatile
        private var readFailure: Throwable? = null

        private val readerThread = Thread({
            runCatching {
                process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { reader ->
                    val buffer = CharArray(2048)
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) {
                            break
                        }
                        synchronized(output) {
                            output.append(buffer, 0, read)
                        }
                    }
                }
            }.onFailure { error ->
                // 进程被 destroyForcibly 时流关闭属于预期路径，不升级为 guardian 自身故障。
                if (error !is java.io.IOException) {
                    readFailure = error
                }
            }
        }, "ProcessGuardian-jcmd-output").apply {
            isDaemon = true
        }

        init {
            readerThread.start()
        }

        /**
         * 在限定时间内等待读取线程收尾，并返回当前已收集的输出文本。
         */
        fun awaitOutput(joinTimeoutMs: Long): String {
            readerThread.join(joinTimeoutMs)
            return synchronized(output) {
                output.toString()
            }
        }

        /**
         * 判断异步读取线程是否仍未结束，用于把潜在卡顿显式标记为降级结果。
         */
        fun isAlive(): Boolean = readerThread.isAlive

        /**
         * 返回读取阶段的失败原因；若读取线程正常结束则为 null。
         */
        fun failureMessage(): String? = readFailure?.message
    }

    /**
     * 单个 NMT 分区摘要。
     */
    private data class NativeMemorySection(
        val name: String,
        val reservedMB: Long,
        val committedMB: Long,
    )

    /**
     * 单个 NMT 分区在相邻采样窗口中的增量信息。
     */
    private data class NativeMemorySectionDelta(
        val name: String,
        val deltaReservedMB: Long,
        val deltaCommittedMB: Long,
        val currentReservedMB: Long,
        val currentCommittedMB: Long,
    )

    /**
     * Native 增长与任务活动的相关性线索。
     */
    private data class NativeTaskCorrelation(
        val taskName: String,
        val score: Long,
        val operationDelta: Long,
        val activeSessions: Int,
        val clientRunningCalls: Int,
        val clientQueuedCalls: Int,
        val evidence: String,
    )

    /**
     * ownerTag 维度的网络并发压力快照。
     */
    private data class OwnerNetworkPressure(
        val runningCalls: Int,
        val queuedCalls: Int,
        val createdSlots: Int,
    ) {
        companion object {
            val EMPTY = OwnerNetworkPressure(
                runningCalls = 0,
                queuedCalls = 0,
                createdSlots = 0,
            )
        }
    }

    /**
     * VM.native_memory summary 的可降级采样结果。
     */
    private data class NativeMemorySummary(
        val status: String,
        val reason: String?,
        val sampledAt: Long,
        val totalReservedMB: Long?,
        val totalCommittedMB: Long?,
        val sections: List<NativeMemorySection>,
    ) {
        companion object {
            /**
             * 用统一状态表示当前运行环境不支持 NMT 采样。
             */
            fun unavailable(reason: String): NativeMemorySummary {
                return NativeMemorySummary(
                    status = "UNAVAILABLE",
                    reason = reason,
                    sampledAt = System.currentTimeMillis(),
                    totalReservedMB = null,
                    totalCommittedMB = null,
                    sections = emptyList(),
                )
            }
        }
    }

    /**
     * 内存消费者数据类
     */
    private data class MemoryConsumer(
        val name: String,
        val estimatedMB: Long,
        val description: String
    )
}
