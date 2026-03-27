package top.bilibili.tasker

import kotlinx.coroutines.CompletableDeferred
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
    fun cleanup() {
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

        t1.cleanupCompleted.await()
        t2.cleanupCompleted.await()

        assertTrue(report.success)
        assertEquals(2, report.totalTaskers)
        assertEquals(2, report.stoppedTaskers)
        assertEquals(0, report.failedTaskers)
        assertTrue(BiliTasker.taskers.isEmpty())
    }

    @Test
    fun `managed workers should be tracked in task health snapshot`() = runBlocking {
        val tasker = ManagedWorkerTasker("ListenerTasker")

        tasker.start()
        tasker.workerReady.await()

        val snapshot = tasker.healthSnapshot()

        assertTrue(snapshot.healthy)
        assertEquals(1, snapshot.workerSnapshots.size)
        assertEquals("listener-loop", snapshot.workerSnapshots.single().workerName)
        assertTrue(snapshot.workerSnapshots.single().active)

        tasker.cancel()
    }
}
