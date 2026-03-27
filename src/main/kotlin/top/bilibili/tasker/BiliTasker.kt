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
import top.bilibili.connector.ConnectionBackoffPolicy
import top.bilibili.core.BiliBiliBot
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.BusinessLifecycleSession
import top.bilibili.core.resource.TaskResourcePolicyRegistry
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
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

data class TaskWorkerSnapshot(
    val workerName: String,
    val active: Boolean,
    val restartCount: Int,
    val lastFailureMessage: String?,
    val restartExhausted: Boolean,
)

data class TaskHealthSnapshot(
    val taskerName: String,
    val active: Boolean,
    val workerSnapshots: List<TaskWorkerSnapshot>,
) {
    val healthy: Boolean
        get() = active && workerSnapshots.none { it.restartExhausted || (!it.active && it.lastFailureMessage != null) }
}

@OptIn(InternalForInheritanceCoroutinesApi::class)
abstract class BiliTasker(
    private val taskerName: String? = null,
) : CoroutineScope, CompletableJob by SupervisorJob(BiliBiliBot.coroutineContext[Job]) {
    override val coroutineContext: CoroutineContext
        get() = this + CoroutineName(taskerName ?: this::class.simpleName ?: "Tasker")

    private val taskDisplayName: String
        get() = taskerName ?: this::class.simpleName ?: "UnknownTasker"

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
    private val managedWorkers = ConcurrentHashMap<String, ManagedWorkerState>()
    private val managedWorkerDefinitions = ConcurrentHashMap<String, ManagedWorkerDefinition>()

    abstract var interval: Int
    open val unitTime: Long = 1000
    protected open val wrapMainInBusinessLifecycle: Boolean = true

    protected open fun init() {}

    protected open fun before() {}
    protected abstract suspend fun main()
    protected open fun after() {}

    /**
     * 为长生命周期子循环注册受管 worker，统一追踪运行状态、失败原因和有限次自愈重启。
     */
    protected fun launchManagedWorker(
        workerName: String,
        maxRestarts: Int = Int.MAX_VALUE,
        backoffPolicy: ConnectionBackoffPolicy = ConnectionBackoffPolicy(baseDelayMillis = 1_000L, maxDelayMillis = 30_000L),
        block: suspend () -> Unit,
    ): Job {
        val definition = ManagedWorkerDefinition(
            workerName = workerName,
            maxRestarts = maxRestarts,
            backoffPolicy = backoffPolicy,
            block = block,
        )
        val existing = managedWorkerDefinitions.putIfAbsent(workerName, definition)
        require(existing == null) { "重复注册受管 worker: $workerName" }
        return launchManagedWorker(definition, allowReplace = false)
    }

    fun healthSnapshot(): TaskHealthSnapshot {
        val snapshots = managedWorkers.values
            .sortedBy { it.workerName }
            .map { state ->
                TaskWorkerSnapshot(
                    workerName = state.workerName,
                    active = state.running && state.job?.isActive == true,
                    restartCount = state.restartCount,
                    lastFailureMessage = state.lastFailureMessage,
                    restartExhausted = state.restartExhausted,
                )
            }

        return TaskHealthSnapshot(
            taskerName = taskDisplayName,
            active = job?.isActive == true,
            workerSnapshots = snapshots,
        )
    }

    /**
     * 为守护进程提供 tasker 级恢复入口：仅重建当前 tasker 已注册的受管 worker，不重启整个 tasker 作业。
     */
    fun recoverUnhealthyWorkers(): Boolean {
        val parentJob = job ?: return false
        if (!parentJob.isActive || BiliBiliBot.isStopping()) {
            return false
        }

        val snapshot = healthSnapshot()
        if (snapshot.healthy || snapshot.workerSnapshots.isEmpty()) {
            return false
        }

        // 仅重建已登记的受管 worker，保持 tasker 主生命周期和业务上下文不变。
        managedWorkers.values.forEach { state ->
            state.job?.cancel()
        }
        managedWorkers.clear()
        managedWorkerDefinitions.values
            .sortedBy { definition -> definition.workerName }
            .forEach { definition ->
                launchManagedWorker(definition, allowReplace = true)
            }
        BiliBiliBot.logger.warn("任务 {} 已触发受管 worker 恢复", taskDisplayName)
        return true
    }

    protected suspend fun <T> runBusinessOperation(
        operation: String,
        block: suspend BusinessLifecycleSession.() -> T,
    ): T {
        val policy = TaskResourcePolicyRegistry.policyOf(taskDisplayName)
            ?: error("任务未声明资源策略: $taskDisplayName")
        return BusinessLifecycleManager.run(
            owner = taskDisplayName,
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
            TaskerStopFailure(taskerName = taskDisplayName, error = "停止超时(${timeoutMs}ms)")
        } catch (e: CancellationException) {
            if (BiliBiliBot.isStopping() || !currentJob.isActive) {
                null
            } else {
                TaskerStopFailure(taskerName = taskDisplayName, error = e.message ?: e::class.simpleName.orEmpty())
            }
        } catch (e: Exception) {
            TaskerStopFailure(taskerName = taskDisplayName, error = e.message ?: e::class.simpleName.orEmpty())
        }
    }

    override fun start(): Boolean {
        // 预留未使用变量 policy: val policy = TaskResourcePolicyRegistry.policyOf(taskDisplayName)
        TaskResourcePolicyRegistry.policyOf(taskDisplayName)
            ?: error("任务未声明资源策略: $taskDisplayName")

        // 先以 LAZY 方式创建主任务协程并发布 job，再进入 init，避免受管 worker 在初始化阶段读到空父 Job。
        val taskJob = launch(coroutineContext, start = kotlinx.coroutines.CoroutineStart.LAZY) {
            var consecutiveErrors = 0
            val maxErrors = 10

            try {
                init()
            } catch (e: Exception) {
                BiliBiliBot.logger.error("任务 ${this::class.simpleName} 初始化失败", e)
                return@launch
            }

            if (interval == -1) {
                runCatching {
                    executeIteration("run-once")
                }.onFailure { e ->
                    if (isExpectedShutdownThrowable(e) && BiliBiliBot.isStopping()) {
                        BiliBiliBot.logger.info("${this::class.simpleName} 在停机期间已停止")
                    } else {
                        BiliBiliBot.logger.error("一次性任务 ${this::class.simpleName} 执行失败", e)
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
                            BiliBiliBot.logger.info("${this::class.simpleName} 在停机期间已停止")
                            break
                        }

                        consecutiveErrors++
                        BiliBiliBot.logger.error("任务 ${this::class.simpleName} 执行失败 ($consecutiveErrors/$maxErrors)", failure)

                        if (consecutiveErrors >= maxErrors) {
                            BiliBiliBot.logger.error("任务 ${this::class.simpleName} 连续失败 $maxErrors 次，停止任务")
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
                BiliBiliBot.logger.info("${this::class.simpleName} 已停止")
            }
        }
        job = taskJob
        taskJob.start()

        return taskers.add(this)
    }

    override fun cancel(cause: CancellationException?) {
        managedWorkers.values.forEach { state ->
            state.job?.cancel(cause)
        }
        job?.cancel(cause)
        coroutineContext.cancelChildren(cause)
        taskers.remove(this)
    }

    /**
     * 统一驱动 worker 自愈循环；仅在明确失败或异常退出时做有限次重启。
     */
    private suspend fun runManagedWorkerLoop(
        state: ManagedWorkerState,
        maxRestarts: Int,
        backoffPolicy: ConnectionBackoffPolicy,
        block: suspend () -> Unit,
    ) {
        while (job?.isActive == true && !BiliBiliBot.isStopping()) {
            state.running = true
            try {
                block()
                state.running = false
                if (job?.isActive == true && !BiliBiliBot.isStopping()) {
                    recordWorkerFailure(state, "worker exited unexpectedly", maxRestarts, backoffPolicy)
                    if (state.restartExhausted) {
                        return
                    }
                    continue
                }
                return
            } catch (e: CancellationException) {
                state.running = false
                if (BiliBiliBot.isStopping() || job?.isActive != true) {
                    return
                }
                throw e
            } catch (e: Exception) {
                state.running = false
                recordWorkerFailure(state, e.message ?: e::class.simpleName.orEmpty(), maxRestarts, backoffPolicy)
                if (state.restartExhausted) {
                    return
                }
            }
        }
    }

    /**
     * 记录 worker 故障并按退避策略延迟重启；超过预算后将健康状态标记为失败。
     */
    private suspend fun recordWorkerFailure(
        state: ManagedWorkerState,
        message: String,
        maxRestarts: Int,
        backoffPolicy: ConnectionBackoffPolicy,
    ) {
        state.lastFailureMessage = message
        if (state.restartCount >= maxRestarts) {
            state.restartExhausted = true
            BiliBiliBot.logger.error("任务 ${this::class.simpleName} 的 worker ${state.workerName} 已耗尽重启预算: $message")
            return
        }

        state.restartCount += 1
        val backoffDelay = backoffPolicy.nextDelayMillis(state.restartCount)
        BiliBiliBot.logger.warn(
            "任务 {} 的 worker {} 失败，将在 {}ms 后第 {} 次重启: {}",
            this::class.simpleName,
            state.workerName,
            backoffDelay,
            state.restartCount,
            message,
        )
        kotlinx.coroutines.delay(backoffDelay)
    }

    private data class ManagedWorkerState(
        val workerName: String,
        var job: Job? = null,
        var running: Boolean = false,
        var restartCount: Int = 0,
        var lastFailureMessage: String? = null,
        var restartExhausted: Boolean = false,
    )

    /**
     * 保留 worker 的原始启动定义，供 guardian 恢复时按同样约束重新拉起。
     */
    private data class ManagedWorkerDefinition(
        val workerName: String,
        val maxRestarts: Int,
        val backoffPolicy: ConnectionBackoffPolicy,
        val block: suspend () -> Unit,
    )

    /**
     * 统一按登记定义拉起 worker，恢复路径可复用这里而不必重复调用各 tasker 的 init。
     */
    private fun launchManagedWorker(
        definition: ManagedWorkerDefinition,
        allowReplace: Boolean,
    ): Job {
        val parentJob = job ?: error("任务主协程尚未启动，无法注册受管 worker: ${definition.workerName}")
        val state = ManagedWorkerState(workerName = definition.workerName)
        val existing = if (allowReplace) {
            managedWorkers.put(definition.workerName, state)
        } else {
            managedWorkers.putIfAbsent(definition.workerName, state)
        }
        require(allowReplace || existing == null) { "重复注册受管 worker: ${definition.workerName}" }

        val workerJob = launch(parentJob + CoroutineName("$taskDisplayName.worker.${definition.workerName}")) {
            runManagedWorkerLoop(state, definition.maxRestarts, definition.backoffPolicy, definition.block)
        }
        state.job = workerJob
        return workerJob
    }
}
