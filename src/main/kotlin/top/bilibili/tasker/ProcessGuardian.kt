package top.bilibili.tasker

import kotlinx.coroutines.isActive
import org.slf4j.LoggerFactory
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.ImageCache
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.lang.management.ManagementFactory
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    // 连接状态追踪
    private var lastConnectionStatus = true
    private var disconnectedDuration = 0

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

        // 2. 检查内存使用
        checkMemoryUsage(report)

        // 2.5 检查非堆内存 (Metaspace, CodeCache)
        checkNonHeapMemory(report)

        // 3. 检查连接状态
        checkConnectionStatus(report)

        // 4. 清理僵尸任务
        cleanDeadTaskers(report)

        // 5. ✅ P3修复: 检查 Channel 背压
        checkChannelBackpressure(report)

        // 6. 写入监控日志（只在有异常时写入，或每10分钟写入一次状态）
        writeMonitorLog(report)

        logger.debug("系统健康检查完成")
    }

    /**
     * 检查任务健康状态
     */
    private fun checkTaskerHealth(report: MonitorReport) {
        val unhealthyTaskers = taskers
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
            logger.debug("所有任务运行正常，活跃任务数: ${taskers.count { it.healthSnapshot().healthy }}")
        }
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
     * 收集内存占用最高的组件信息
     * 由于 JVM 无法直接获取每个对象的内存占用，这里估算主要组件的内存使用
     */
    private fun collectTopMemoryConsumers(): List<MemoryConsumer> {
        val consumers = mutableListOf<MemoryConsumer>()

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
            estimatedMB = (taskers.size * 1024L) / 1024 / 1024,  // 估算每个任务约1KB
            description = "${taskers.size} 个任务"
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

        val runtimeStatus = BiliBiliBot.platformAdapter.runtimeStatus()
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
        val beforeCount = taskers.size
        val deadTaskers = taskers.filter { it != this && !it.isActive }

        if (deadTaskers.isNotEmpty()) {
            // 从列表中移除僵尸任务
            taskers.removeAll(deadTaskers.toSet())
            val removedCount = beforeCount - taskers.size

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
                val runtimeStatus = BiliBiliBot.platformAdapter.runtimeStatus()
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
        val hasAnyIssue = report.hasTaskerIssue ||
                report.hasMemoryIssue ||
                report.hasNonHeapIssue ||
                report.hasConnectionIssue ||
                report.hasZombieTaskers ||
                report.hasBackpressure  // ✅ P3修复: 添加背压检测

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
    )

    /**
     * 内存消费者数据类
     */
    private data class MemoryConsumer(
        val name: String,
        val estimatedMB: Long,
        val description: String
    )
}
