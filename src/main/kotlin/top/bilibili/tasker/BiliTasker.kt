package top.bilibili.tasker

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.BusinessLifecycleSession
import top.bilibili.core.resource.TaskResourcePolicyRegistry
import top.bilibili.utils.logger
import java.util.Collections
import kotlin.coroutines.CoroutineContext

data class TaskerStopFailure(
    val taskerName: String,
    val error: String,
)

data class TaskerStopReport(
    val success: Boolean,
    val totalTaskers: Int,
    val stoppedTaskers: Int,
    val failedTaskers: Int,
    val failures: List<TaskerStopFailure>,
)

@OptIn(InternalForInheritanceCoroutinesApi::class)
abstract class BiliTasker(
    private val taskerName: String? = null,
) : CoroutineScope, CompletableJob by SupervisorJob(BiliBiliBot.coroutineContext[Job]) {
    override val coroutineContext: CoroutineContext
        get() = this + CoroutineName(taskerName ?: this::class.simpleName ?: "Tasker")

    companion object {
        private const val DEFAULT_STOP_TIMEOUT_MS = 10_000L
        val taskers = Collections.synchronizedList(mutableListOf<BiliTasker>())

        fun cancelAll(): TaskerStopReport = runBlocking {
            cancelAll(DEFAULT_STOP_TIMEOUT_MS)
        }

        suspend fun cancelAll(timeoutMs: Long): TaskerStopReport {
            val snapshot = synchronized(taskers) { taskers.toList() }
            snapshot.forEach { tasker -> tasker.cancel() }

            val results = coroutineScope {
                snapshot.map { tasker ->
                    async {
                        tasker.awaitStop(timeoutMs)
                    }
                }.awaitAll()
            }

            val failures = results.filterNotNull()
            val stoppedCount = results.count { it == null }

            return TaskerStopReport(
                success = failures.isEmpty(),
                totalTaskers = snapshot.size,
                stoppedTaskers = stoppedCount,
                failedTaskers = failures.size,
                failures = failures,
            )
        }
    }

    private var job: Job? = null

    abstract var interval: Int
    open val unitTime: Long = 1000
    protected open val wrapMainInBusinessLifecycle: Boolean = true

    protected open fun init() {}

    protected open fun before() {}
    protected abstract suspend fun main()
    protected open fun after() {}

    protected suspend fun <T> runBusinessOperation(
        operation: String,
        block: suspend BusinessLifecycleSession.() -> T,
    ): T {
        val taskName = this::class.simpleName ?: "UnknownTasker"
        val policy = TaskResourcePolicyRegistry.policyOf(taskName)
            ?: error("任务未声明资源策略: $taskName")
        return BusinessLifecycleManager.run(
            owner = taskName,
            operation = operation,
            strictness = policy.strictness,
            block = block,
        )
    }

    private suspend fun executeIteration(operation: String) {
        if (wrapMainInBusinessLifecycle) {
            runBusinessOperation(operation) {
                before()
                this@BiliTasker.main()
                after()
            }
        } else {
            before()
            main()
            after()
        }
    }

    private fun isExpectedShutdownThrowable(throwable: Throwable): Boolean {
        return throwable is CancellationException ||
            throwable is ClosedReceiveChannelException ||
            throwable is ClosedSendChannelException
    }

    private suspend fun awaitStop(timeoutMs: Long): TaskerStopFailure? {
        val taskName = this::class.simpleName ?: "UnknownTasker"
        val currentJob = job

        if (currentJob == null) {
            return null
        }

        return try {
            withTimeout(timeoutMs) {
                currentJob.join()
            }
            null
        } catch (_: TimeoutCancellationException) {
            TaskerStopFailure(taskerName = taskName, error = "停止超时(${timeoutMs}ms)")
        } catch (e: CancellationException) {
            if (BiliBiliBot.isStopping() || !currentJob.isActive) {
                null
            } else {
                TaskerStopFailure(taskerName = taskName, error = e.message ?: e::class.simpleName.orEmpty())
            }
        } catch (e: Exception) {
            TaskerStopFailure(taskerName = taskName, error = e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override fun start(): Boolean {
        val taskName = this::class.simpleName ?: "UnknownTasker"
        // 预留未使用变量 policy: val policy = TaskResourcePolicyRegistry.policyOf(taskName)
        TaskResourcePolicyRegistry.policyOf(taskName)
            ?: error("任务未声明资源策略: $taskName")

        job = launch(coroutineContext) {
            var consecutiveErrors = 0
            val maxErrors = 10

            try {
                init()
            } catch (e: Exception) {
                logger.error("任务 ${this::class.simpleName} 初始化失败", e)
                return@launch
            }

            if (interval == -1) {
                runCatching {
                    executeIteration("run-once")
                }.onFailure { e ->
                    if (isExpectedShutdownThrowable(e) && BiliBiliBot.isStopping()) {
                        logger.info("${this::class.simpleName} 在停机期间已停止")
                    } else {
                        logger.error("一次性任务 ${this::class.simpleName} 执行失败", e)
                    }
                }
            } else {
                while (isActive) {
                    val result = runCatching {
                        executeIteration("tick")
                    }

                    if (result.isFailure) {
                        val failure = result.exceptionOrNull()
                        if (failure != null && isExpectedShutdownThrowable(failure) && (BiliBiliBot.isStopping() || !isActive)) {
                            logger.info("${this::class.simpleName} 在停机期间已停止")
                            break
                        }

                        consecutiveErrors++
                        logger.error("任务 ${this::class.simpleName} 执行失败 ($consecutiveErrors/$maxErrors)", failure)

                        if (consecutiveErrors >= maxErrors) {
                            logger.error("任务 ${this::class.simpleName} 连续失败 $maxErrors 次，停止任务")
                            break
                        }

                        val backoffDelay = minOf(120000L, consecutiveErrors * 10000L)
                        kotlinx.coroutines.delay(backoffDelay)
                    } else {
                        consecutiveErrors = 0
                    }

                    kotlinx.coroutines.delay(interval * unitTime)
                }
            }

            if (!isActive) {
                logger.info("${this::class.simpleName} 已停止")
            }
        }

        return taskers.add(this)
    }

    override fun cancel(cause: CancellationException?) {
        job?.cancel(cause)
        coroutineContext.cancelChildren(cause)
        taskers.remove(this)
    }
}
