package top.bilibili.tasker

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.connector.ConnectionBackoffPolicy

class TaskSelfHealingTest {
    private class RecoveringWorkerTasker : BiliTasker("ListenerTasker") {
        override var interval: Int = -1
        override val wrapMainInBusinessLifecycle = false
        private val attempts = AtomicInteger(0)
        val recovered = CompletableDeferred<Unit>()

        override fun init() {
            launchManagedWorker(
                workerName = "recovering-loop",
                maxRestarts = 2,
                backoffPolicy = ConnectionBackoffPolicy(baseDelayMillis = 10L, maxDelayMillis = 20L),
            ) {
                if (attempts.incrementAndGet() == 1) {
                    error("boom")
                }
                recovered.complete(Unit)
                awaitCancellation()
            }
        }

        override suspend fun main() {
            awaitCancellation()
        }
    }

    private class ExhaustedWorkerTasker : BiliTasker("SendTasker") {
        override var interval: Int = -1
        override val wrapMainInBusinessLifecycle = false

        override fun init() {
            launchManagedWorker(
                workerName = "broken-loop",
                maxRestarts = 1,
                backoffPolicy = ConnectionBackoffPolicy(baseDelayMillis = 10L, maxDelayMillis = 20L),
            ) {
                error("still broken")
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
    fun `managed worker should restart with bounded backoff and recover health`() = runBlocking {
        val tasker = RecoveringWorkerTasker()

        tasker.start()
        withTimeout(1_000) {
            tasker.recovered.await()
        }

        val snapshot = tasker.healthSnapshot()

        assertTrue(snapshot.healthy)
        assertEquals(1, snapshot.workerSnapshots.single().restartCount)
        assertTrue(snapshot.workerSnapshots.single().active)

        tasker.cancel()
    }

    @Test
    fun `worker failure should surface in task health after restart budget is exhausted`() = runBlocking {
        val tasker = ExhaustedWorkerTasker()

        tasker.start()

        withTimeout(1_000) {
            while (!tasker.healthSnapshot().workerSnapshots.single().restartExhausted) {
                delay(10)
            }
        }

        val snapshot = tasker.healthSnapshot()
        assertFalse(snapshot.healthy)
        assertTrue(snapshot.workerSnapshots.single().lastFailureMessage?.contains("still broken") == true)
        assertTrue(snapshot.workerSnapshots.single().restartExhausted)

        tasker.cancel()
    }
}
