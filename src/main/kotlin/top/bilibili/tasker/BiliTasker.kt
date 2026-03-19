package top.bilibili.tasker

import kotlinx.coroutines.*
import top.bilibili.core.resource.BusinessLifecycleManager
import top.bilibili.core.resource.BusinessLifecycleSession
import top.bilibili.core.resource.TaskResourcePolicyRegistry
import top.bilibili.core.BiliBiliBot
import top.bilibili.utils.logger
import kotlin.coroutines.CoroutineContext

abstract class BiliTasker(
    private val taskerName: String? = null
) : CoroutineScope, CompletableJob by SupervisorJob(BiliBiliBot.coroutineContext.job) {
    override val coroutineContext: CoroutineContext
        get() = this + CoroutineName(taskerName ?: this::class.simpleName ?: "Tasker")

    companion object {
        val taskers = mutableListOf<BiliTasker>()

        fun cancelAll() {
            taskers.toList().forEach {
                it.cancel()
            }
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

    override fun start(): Boolean {
        val taskName = this::class.simpleName ?: "UnknownTasker"
        val policy = TaskResourcePolicyRegistry.policyOf(taskName)
            ?: error("任务未声明资源策略: $taskName")

        job = launch(coroutineContext) {
            var consecutiveErrors = 0
            val maxErrors = 10

            // ✅ 初始化失败直接退出
            try {
                init()
            } catch (e: Exception) {
                logger.error("任务 ${this::class.simpleName} 初始化失败", e)
                return@launch
            }

            if (interval == -1) {
                // 一次性任务
                runCatching {
                    executeIteration("run-once")
                }.onFailure { e ->
                    logger.error("一次性任务 ${this::class.simpleName} 执行失败", e)
                }
            } else {
                // 周期性任务
                while (isActive) {
                    val result = runCatching {
                        executeIteration("tick")
                    }

                    if (result.isFailure) {
                        consecutiveErrors++
                        logger.error("任务 ${this::class.simpleName} 执行失败 ($consecutiveErrors/$maxErrors)", result.exceptionOrNull())

                        // ✅ 连续失败过多则停止任务
                        if (consecutiveErrors >= maxErrors) {
                            logger.error("任务 ${this::class.simpleName} 连续失败 $maxErrors 次，停止任务")
                            break
                        }

                        // ✅ 指数退避策略
                        val backoffDelay = minOf(120000L, consecutiveErrors * 10000L)
                        delay(backoffDelay)
                    } else {
                        // ✅ 成功后重置计数器
                        consecutiveErrors = 0
                    }

                    delay(interval * unitTime)
                }
            }
            if (!isActive) logger.error("${this::class.simpleName} 已停止工作!")
        }

        return taskers.add(this)
    }

    override fun cancel(cause: CancellationException?) {
        job?.cancel(cause)
        coroutineContext.cancelChildren(cause)
        taskers.remove(this)  // ✅ P0修复: 从列表中移除，防止内存泄漏
    }
}
