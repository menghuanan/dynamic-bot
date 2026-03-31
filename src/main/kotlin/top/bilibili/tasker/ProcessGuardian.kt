package top.bilibili.tasker

import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import top.bilibili.client.BiliClient
import top.bilibili.connector.PlatformObservabilitySnapshot
import top.bilibili.core.BiliBiliBot
import top.bilibili.skia.SkiaManager
import top.bilibili.utils.ImageCache
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.lang.management.ThreadMXBean
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

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
    private const val CODECACHE_LIMIT_MB = 48L
    private const val NON_HEAP_WARNING_THRESHOLD = 0.8  // 80%
    private const val NATIVE_MEMORY_SAMPLE_INTERVAL_MS = 10 * 60 * 1000L
    private const val NATIVE_MEMORY_COMMAND_TIMEOUT_MS = 5_000L
    private const val NATIVE_MEMORY_DESTROY_WAIT_MS = 500L
    private const val PROCESS_OUTPUT_DRAIN_TIMEOUT_MS = 1_000L

    // 连接状态追踪
    private var lastConnectionStatus = true
    private var disconnectedDuration = 0
    private var lastNativeMemorySampleAtMillis = 0L
    private var lastNativeMemorySummary: NativeMemorySummary? = null

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

        // 6. 写入监控日志（只在有异常时写入，或每10分钟写入一次状态）
        writeMonitorLog(report)

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

        for (pool in memoryPools) {
            val usage = pool.usage ?: continue
            val usedMB = usage.used / 1024 / 1024

            when {
                pool.name.contains("Metaspace", ignoreCase = true) -> {
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
                    report.codeCacheUsedMB += usedMB
                }
            }
        }

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
     * 收集进程、线程、受管协程和 Skia 运行态快照。
     */
    private fun collectRuntimeResourceSnapshot(report: MonitorReport) {
        report.processMetrics = collectProcessMetrics()
        report.bufferPools = collectBufferPoolMetrics()
        report.threadMetrics = collectThreadMetrics()
        report.coroutineMetrics = collectManagedCoroutineMetrics()
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
        return BiliBiliBot.requireConnectorManager().runtimeObservability()
    }

    /**
     * 采集当前 Java 进程的轻量级资源指标。
     */
    private fun collectProcessMetrics(): ProcessMetrics {
        val currentProcess = ProcessHandle.current()
        val procStatus = readProcStatusSnapshot()
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
     * 仅在异常或固定周期执行一次 jcmd VM.native_memory summary，并在不可用时自动降级。
     */
    private fun captureNativeMemorySummary(report: MonitorReport) {
        val now = System.currentTimeMillis()
        if (!shouldCaptureNativeSummary(report, now)) {
            report.nativeMemorySummary = lastNativeMemorySummary
            return
        }

        val summary = collectNativeMemorySummary()
        lastNativeMemorySampleAtMillis = now
        lastNativeMemorySummary = summary
        report.nativeMemorySummary = summary
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

        // 先异步排空 stdout/stderr，再等待退出，避免 jcmd 因管道写满而卡死在 waitFor 之前。
        val outputDrain = drainProcessOutputAsync(process)
        val completed = runCatching {
            process.waitFor(NATIVE_MEMORY_COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrElse { error ->
            runCatching { process.inputStream.close() }
            process.destroyForcibly()
            process.waitFor(NATIVE_MEMORY_DESTROY_WAIT_MS, TimeUnit.MILLISECONDS)
            outputDrain.awaitOutput(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS)
            return NativeMemorySummary.unavailable("jcmd 执行失败: ${error.message}")
        }
        if (!completed) {
            runCatching { process.inputStream.close() }
            process.destroyForcibly()
            process.waitFor(NATIVE_MEMORY_DESTROY_WAIT_MS, TimeUnit.MILLISECONDS)
            outputDrain.awaitOutput(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS)
            return NativeMemorySummary.unavailable("jcmd 执行超时，已降级跳过 VM.native_memory summary")
        }

        val output = outputDrain.awaitOutput(PROCESS_OUTPUT_DRAIN_TIMEOUT_MS)
        if (outputDrain.isAlive()) {
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
        val sectionMatches = Regex("""(?m)^\s*-\s*([A-Za-z ]+)\s*\(reserved=(\d+)KB,\s*committed=(\d+)KB\)""")
            .findAll(output)
            .map { match ->
                NativeMemorySection(
                    name = match.groupValues[1].trim(),
                    reservedMB = match.groupValues[2].toLong() / 1024L,
                    committedMB = match.groupValues[3].toLong() / 1024L,
                )
            }
            .sortedByDescending { it.committedMB }
            .take(8)
            .toList()

        return NativeMemorySummary(
            status = "OK",
            reason = null,
            sampledAt = System.currentTimeMillis(),
            totalReservedMB = totalMatch?.groupValues?.getOrNull(1)?.toLongOrNull()?.div(1024L),
            totalCommittedMB = totalMatch?.groupValues?.getOrNull(2)?.toLongOrNull()?.div(1024L),
            topSections = sectionMatches,
        )
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
            ?: "jcmd".takeIf { runCatching { ProcessBuilder(it, "-h").start().destroy() }.isSuccess }
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

        val runtimeStatus = BiliBiliBot.requireConnectorManager().runtimeStatus()
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

        // 每10分钟（20次检查）写入一次正常状态日志
        val shouldWriteNormalLog = !hasAnyIssue && (System.currentTimeMillis() / 1000 / 60 % 10 == 0L)

        if (!hasAnyIssue && !shouldWriteNormalLog) {
            return
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

                // 2.12 BiliClient / OkHttp 运行态
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

                // 2.13 平台 transport HttpClient / OkHttp 运行态
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

                // 2.14 Skia 运行态
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

                // 2.15 可降级的 Native Memory Tracking 摘要
                report.nativeMemorySummary?.let { native ->
                    writer.println("[Native Memory Summary]")
                    writer.println("  状态: ${native.status}")
                    native.reason?.let { writer.println("  说明: $it") }
                    if (native.status == "OK") {
                        writer.println(
                            "  Total reserved=${native.totalReservedMB?.let { "${it}MB" } ?: "未知"}, " +
                                "committed=${native.totalCommittedMB?.let { "${it}MB" } ?: "未知"}"
                        )
                        native.topSections.forEach { section ->
                            writer.println("    - ${section.name}: reserved=${section.reservedMB}MB, committed=${section.committedMB}MB")
                        }
                    }
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
        var metaspaceUsedMB: Long = 0,
        var codeCacheUsedMB: Long = 0,
        var nonHeapIssueDetails: MutableList<String> = mutableListOf(),

        // 进程 / 线程 / 协程 / Skia 轻量快照
        var processMetrics: ProcessMetrics? = null,
        var bufferPools: List<BufferPoolMetrics> = emptyList(),
        var threadMetrics: ThreadMetrics? = null,
        var coroutineMetrics: CoroutineMetrics? = null,
        var biliClientMetrics: top.bilibili.client.BiliClientRuntimeSnapshot? = null,
        var platformObservability: PlatformObservabilitySnapshot = PlatformObservabilitySnapshot.empty("platform adapter is not initialized"),
        var skiaStatus: top.bilibili.skia.SkiaManagerStatus? = null,
        var nativeMemorySummary: NativeMemorySummary? = null,

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
     * VM.native_memory summary 的可降级采样结果。
     */
    private data class NativeMemorySummary(
        val status: String,
        val reason: String?,
        val sampledAt: Long,
        val totalReservedMB: Long?,
        val totalCommittedMB: Long?,
        val topSections: List<NativeMemorySection>,
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
                    topSections = emptyList(),
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
