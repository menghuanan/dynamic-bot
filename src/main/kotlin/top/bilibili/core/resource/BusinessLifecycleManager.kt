package top.bilibili.core.resource

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import top.bilibili.core.BiliBiliBot
import java.io.Closeable
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class BusinessLifecycleSession(
    val owner: String,
    val operation: String,
    private val logger: Logger,
) : AutoCloseable {
    private val cleanupActions = Collections.synchronizedList(mutableListOf<() -> Unit>())
    private var closed = false

    fun track(closeable: AutoCloseable?) {
        if (closeable == null) return
        onFinally {
            closeable.close()
        }
    }

    fun track(closeable: Closeable?) {
        if (closeable == null) return
        onFinally {
            closeable.close()
        }
    }

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

object BusinessLifecycleManager {
    private val logger = LoggerFactory.getLogger(BusinessLifecycleManager::class.java)
    private val activeSessions = AtomicInteger(0)

    fun activeCount(): Int = activeSessions.get()

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
            return if (strictness.businessHardTimeoutMs == null) {
                session.block()
            } else {
                withTimeout(strictness.businessHardTimeoutMs) {
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
            if (duration > strictness.businessWarnThresholdMs && !BiliBiliBot.isStopping()) {
                logger.warn(
                    "资源会话运行时间过长: owner=$owner, operation=$operation, strictness=$strictness, duration=${duration}ms, activeSessions=$active",
                )
            }
        }
    }
}
