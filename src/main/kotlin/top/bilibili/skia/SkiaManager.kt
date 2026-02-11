package top.bilibili.skia

import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import top.bilibili.utils.ImageCache
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Skia 资源管理器 - 统一入口
 */
object SkiaManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val logger = LoggerFactory.getLogger(SkiaManager::class.java)

    init {
        // Validate configuration on initialization
        try {
            SkiaConfig.validate()
            logger.info("SkiaManager initialized with valid configuration")
        } catch (e: IllegalArgumentException) {
            logger.error("Invalid SkiaConfig detected", e)
            throw e
        }
    }

    // 运行模式
    enum class Mode { IN_PROCESS, WORKER_PROCESS }
    private var currentMode = AtomicReference(Mode.IN_PROCESS)

    // 统计信息
    private val totalDrawingCount = AtomicLong(0)
    private val totalCleanupCount = AtomicLong(0)
    private val startTime = System.currentTimeMillis()

    /**
     * 执行绘图任务（统一入口）
     */
    suspend fun <T> executeDrawing(block: suspend DrawingSession.() -> T): T {
        totalDrawingCount.incrementAndGet()

        return when (currentMode.get()) {
            Mode.IN_PROCESS -> executeInProcess(block)
            Mode.WORKER_PROCESS -> throw UnsupportedOperationException("Worker process mode not implemented yet")
        }
    }

    /**
     * 进程内执行
     */
    private suspend fun <T> executeInProcess(block: suspend DrawingSession.() -> T): T {
        return DrawingQueueManager.submit {
            DrawingSession().use { session ->
                session.block()
            }
        }
    }

    /**
     * 执行清理
     */
    suspend fun performCleanup() {
        totalCleanupCount.incrementAndGet()
        logger.info("开始执行 Skia 清理...")

        // 1. 等待活动任务完成（带超时）
        withTimeoutOrNull(30_000L) {
            DrawingQueueManager.awaitAllCompleted()
        } ?: logger.warn("等待活动任务完成超时，强制继续清理")

        // 2. 清理全局缓存
        clearGlobalCaches()

        // 3. 强制 GC
        repeat(3) {
            System.gc()
            System.runFinalization()
            delay(100)
        }

        logger.info("Skia 清理完成")
    }

    /**
     * 清理全局缓存
     */
    private fun clearGlobalCaches() {
        // 清理图片缓存
        runCatching {
            ImageCache.cleanCache()
            logger.debug("ImageCache cleared successfully")
        }.onFailure { e ->
            logger.warn("Failed to clear ImageCache", e)
        }
    }

    /**
     * 获取内存使用率
     */
    private fun getMemoryUsage(): Double {
        val runtime = Runtime.getRuntime()
        val used = runtime.totalMemory() - runtime.freeMemory()
        val max = runtime.maxMemory()
        return used.toDouble() / max.toDouble()
    }

    /**
     * 获取状态信息
     */
    fun getStatus(): SkiaManagerStatus {
        return SkiaManagerStatus(
            mode = currentMode.get(),
            memoryUsage = getMemoryUsage(),
            totalDrawingCount = totalDrawingCount.get(),
            totalCleanupCount = totalCleanupCount.get(),
            queueStatus = DrawingQueueManager.getQueueStatus(),
            uptimeMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * 关闭管理器
     */
    suspend fun shutdown() {
        logger.info("关闭 SkiaManager...")
        performCleanup()
        scope.cancel()
    }
}

/**
 * SkiaManager 状态
 */
data class SkiaManagerStatus(
    val mode: SkiaManager.Mode,
    val memoryUsage: Double,
    val totalDrawingCount: Long,
    val totalCleanupCount: Long,
    val queueStatus: QueueStatus,
    val uptimeMs: Long
)
