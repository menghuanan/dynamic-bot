package top.bilibili.core.resource

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import top.bilibili.core.BiliBiliBot
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 单次业务执行期间的资源清理上下文。
 */
class BusinessLifecycleSession(
    val owner: String,
    val operation: String,
    private val logger: Logger,
) : AutoCloseable {
    private val cleanupActions = Collections.synchronizedList(mutableListOf<() -> Unit>())
    private var closed = false

    /**
     * 在会话结束时自动关闭一个 `AutoCloseable` 资源。
     */
    fun track(closeable: AutoCloseable?) {
        if (closeable == null) return
        onFinally {
            closeable.close()
        }
    }

    /**
     * 在会话结束时自动关闭一个 `Closeable` 资源。
     */
    fun track(closeable: Closeable?) {
        if (closeable == null) return
        onFinally {
            closeable.close()
        }
    }

    /**
     * 注册在会话结束时执行的清理动作。
     */
    fun onFinally(action: () -> Unit) {
        cleanupActions.add(action)
    }

    override fun close() {
        if (closed) return
        closed = true

        var failures = 0
        cleanupActions.asReversed().forEach { action ->
            runCatching { action() }
                .onFailure {
                    failures++
                    logger.warn("业务生命周期清理失败: owner=$owner, operation=$operation, err=${it.message}")
                }
        }

        if (failures > 0) {
            logger.warn("业务生命周期清理完成，但存在失败项: owner=$owner, operation=$operation, failed=$failures")
        }
    }
}

/**
 * 负责包装业务执行的超时控制和清理流程。
 */
object BusinessLifecycleManager {
    private val logger = LoggerFactory.getLogger(BusinessLifecycleManager::class.java)
    private val activeSessions = AtomicInteger(0)

    /**
     * 返回当前活跃的业务生命周期会话数量。
     */
    fun activeCount(): Int = activeSessions.get()

    /**
     * 在资源约束下执行一段业务逻辑，并确保清理动作始终被回收。
     */
    suspend fun <T> run(
        owner: String,
        operation: String,
        strictness: ResourceStrictness = ResourceStrictness.STRICT,
        block: suspend BusinessLifecycleSession.() -> T,
    ): T {
        val session = BusinessLifecycleSession(owner = owner, operation = operation, logger = logger)
        activeSessions.incrementAndGet()
        val startedAt = System.currentTimeMillis()

        try {
            val hardTimeoutMs = strictness.businessHardTimeoutMs
            return if (hardTimeoutMs == null) {
                session.block()
            } else {
                withTimeout(hardTimeoutMs) {
                    session.block()
                }
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn(
                "业务生命周期执行超时: owner=$owner, operation=$operation, strictness=$strictness, timeout=${strictness.businessHardTimeoutMs}ms",
            )
            throw e
        } finally {
            runCatching { session.close() }
                .onFailure {
                    logger.error("关闭业务生命周期会话失败: owner=$owner, operation=$operation, err=${it.message}", it)
                }

            val active = activeSessions.decrementAndGet()
            val duration = System.currentTimeMillis() - startedAt
            // 停机阶段会主动回收大量资源，此时忽略慢会话告警可以减少误报噪音。
            if (duration > strictness.businessWarnThresholdMs && !BiliBiliBot.isStopping()) {
                logger.warn(
                    "资源会话运行时间过长: owner=$owner, operation=$operation, strictness=$strictness, duration=${duration}ms, activeSessions=$active",
                )
            }
        }
    }
}
