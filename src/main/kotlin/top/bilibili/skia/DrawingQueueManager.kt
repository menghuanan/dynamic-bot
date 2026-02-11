package top.bilibili.skia

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 绘图队列管理器
 */
object DrawingQueueManager {
    private val logger = LoggerFactory.getLogger(DrawingQueueManager::class.java)

    private val semaphore = Semaphore(SkiaConfig.maxConcurrent)
    private val activeCount = AtomicInteger(0)
    private val pendingCount = AtomicInteger(0)
    private val isCleaning = AtomicBoolean(false)
    private val lastActivityTime = AtomicLong(System.currentTimeMillis())

    /**
     * 提交绘图任务
     */
    suspend fun <T> submit(block: suspend () -> T): T {
        // 1. 检查队列是否已满
        if (pendingCount.get() >= SkiaConfig.maxQueueSize) {
            throw DrawingQueueFullException("绘图队列已满，请稍后重试")
        }

        // 2. 如果正在清理，等待清理完成
        while (isCleaning.get()) {
            delay(100)
        }

        // 3. 进入等待队列
        pendingCount.incrementAndGet()

        try {
            // 4. 获取执行许可
            semaphore.acquire()
            pendingCount.decrementAndGet()
            activeCount.incrementAndGet()

            // 5. 记录活动时间
            lastActivityTime.set(System.currentTimeMillis())

            // 6. 执行绘图
            return withTimeout(SkiaConfig.drawingTimeoutMs) {
                block()
            }
        } finally {
            activeCount.decrementAndGet()
            semaphore.release()
            lastActivityTime.set(System.currentTimeMillis())
        }
    }

    /**
     * 等待所有活动任务完成
     */
    suspend fun awaitAllCompleted() {
        isCleaning.set(true)
        try {
            // 等待所有活动任务完成
            while (activeCount.get() > 0) {
                delay(100)
            }
        } finally {
            isCleaning.set(false)
        }
    }

    /**
     * 获取队列状态
     */
    fun getQueueStatus(): QueueStatus {
        return QueueStatus(
            pendingCount = pendingCount.get(),
            activeCount = activeCount.get(),
            isFull = pendingCount.get() >= SkiaConfig.maxQueueSize,
            lastActivityTime = lastActivityTime.get()
        )
    }

    /**
     * 检查是否空闲超时
     */
    fun isIdleTimeout(): Boolean {
        val idleTime = System.currentTimeMillis() - lastActivityTime.get()
        return activeCount.get() == 0 && idleTime >= SkiaConfig.idleTimeoutMs
    }
}

/**
 * 队列状态
 */
data class QueueStatus(
    val pendingCount: Int,
    val activeCount: Int,
    val isFull: Boolean,
    val lastActivityTime: Long
)

/**
 * 队列已满异常
 */
class DrawingQueueFullException(message: String) : Exception(message)
