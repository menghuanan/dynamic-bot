package top.bilibili.tasker

import kotlinx.coroutines.CompletableDeferred
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import top.bilibili.connector.ConnectionBackoffPolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiliTaskerRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    private class DummyTasker(name: String) : BiliTasker(name) {
        override var interval: Int = -1
        override suspend fun main() {}
    }

    private class AwaitingTasker(name: String) : BiliTasker(name) {
        override var interval: Int = -1
        override val wrapMainInBusinessLifecycle = false
        val cleanupCompleted = CompletableDeferred<Unit>()

        override suspend fun main() {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    delay(50)
                    cleanupCompleted.complete(Unit)
                }
            }
        }
    }

    private class ManagedWorkerTasker(name: String) : BiliTasker(name) {
        override var interval: Int = -1
        override val wrapMainInBusinessLifecycle = false
        val workerReady = CompletableDeferred<Unit>()

        override fun init() {
            launchManagedWorker(
                workerName = "listener-loop",
                backoffPolicy = ConnectionBackoffPolicy(baseDelayMillis = 10L, maxDelayMillis = 20L),
            ) {
                workerReady.complete(Unit)
                awaitCancellation()
            }
        }

        override suspend fun main() {
            awaitCancellation()
        }
    }

    @AfterTest
    fun cleanup() = runBlocking {
        // 即使断言提前失败，也要先收敛已启动的 tasker，避免后台协程残留导致测试进程无法退出。
        BiliTasker.cancelAll(timeoutMs = 1_000)
        BiliTasker.taskers.clear()
    }

    @Test
    fun `cancelAll should not throw concurrent modification when tasks remove themselves`() {
        val t1 = DummyTasker("dummy-1")
        val t2 = DummyTasker("dummy-2")
        BiliTasker.taskers.add(t1)
        BiliTasker.taskers.add(t2)

        BiliTasker.cancelAll()

        assertTrue(BiliTasker.taskers.isEmpty())
    }

    @Test
    fun `cancelAll should wait for cleanup completion and return stop report`() = runBlocking {
        val t1 = AwaitingTasker("await-1")
        val t2 = AwaitingTasker("await-2")
        val jobField = BiliTasker::class.java.getDeclaredField("job").apply { isAccessible = true }

        jobField.set(
            t1,
            launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(50)
                        t1.cleanupCompleted.complete(Unit)
                    }
                }
            },
        )
        jobField.set(
            t2,
            launch(Dispatchers.Default) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        delay(50)
                        t2.cleanupCompleted.complete(Unit)
                    }
                }
            },
        )
        BiliTasker.taskers.add(t1)
        BiliTasker.taskers.add(t2)

        withTimeout(1_000) {
            while (BiliTasker.taskers.size < 2) {
                delay(10)
            }
        }

        val report = BiliTasker.cancelAll(timeoutMs = 1_000)

        withTimeout(1_000) {
            t1.cleanupCompleted.await()
            t2.cleanupCompleted.await()
        }

        assertTrue(report.success)
        assertEquals(2, report.totalTaskers)
        assertEquals(2, report.stoppedTaskers)
        assertEquals(0, report.failedTaskers)
        assertTrue(BiliTasker.taskers.isEmpty())
    }

    @Test
    fun `managed workers should be tracked in task health snapshot`() = runBlocking {
        val tasker = ManagedWorkerTasker("ListenerTasker")

        try {
            tasker.start()
            // workerReady 需要带超时，避免异步调度竞争时测试无限等待。
            withTimeout(1_000) {
                tasker.workerReady.await()
            }

            val snapshot = tasker.healthSnapshot()

            assertTrue(snapshot.healthy)
            assertEquals(1, snapshot.workerSnapshots.size)
            assertEquals("listener-loop", snapshot.workerSnapshots.single().workerName)
            assertTrue(snapshot.workerSnapshots.single().active)
        } finally {
            tasker.cancel()
        }

    }

    @Test
    fun `start should assign task job before init can register managed workers`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/BiliTasker.kt")

        assertTrue(
            source.contains("start = kotlinx.coroutines.CoroutineStart.LAZY"),
            "BiliTasker.start should create the task coroutine lazily so init cannot run before job is assigned",
        )
        assertTrue(
            source.contains("job = taskJob"),
            "BiliTasker.start should publish the task job before starting execution",
        )
        assertTrue(
            source.contains("taskJob.start()"),
            "BiliTasker.start should explicitly start the task job after publishing it",
        )
    }

    @Test
    fun `run-once cancellation should be treated as expected shutdown signal`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/BiliTasker.kt")

        // 一次性任务被显式 cancel 时应走预期停机分支，避免 CI 日志被无意义堆栈刷屏。
        assertTrue(
            source.contains("isExpectedShutdownThrowable(e) && (BiliBiliBot.isStopping() || !isActive)"),
            "run-once cancellation should be treated as expected shutdown noise when task job is no longer active",
        )
    }
}
