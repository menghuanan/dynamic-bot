package top.bilibili.tasker

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import top.bilibili.connector.ConnectionBackoffPolicy

class ProcessGuardianRecoveryTest {
    private class RecoverableGuardianTasker : BiliTasker("ListenerTasker") {
        override var interval: Int = -1
        override val wrapMainInBusinessLifecycle = false
        private val attempts = AtomicInteger(0)
        val recovered = CompletableDeferred<Unit>()

        override fun init() {
            launchManagedWorker(
                workerName = "guardian-recoverable-loop",
                maxRestarts = 0,
                backoffPolicy = ConnectionBackoffPolicy(baseDelayMillis = 10L, maxDelayMillis = 20L),
            ) {
                if (attempts.incrementAndGet() == 1) {
                    error("guardian recoverable boom")
                }
                recovered.complete(Unit)
                awaitCancellation()
            }
        }

        override suspend fun main() {
            awaitCancellation()
        }
    }

    @AfterTest
    fun cleanup() {
        ProcessGuardian.cancel()
        BiliTasker.taskers.clear()
    }

    /**
     * 通过反射约束 guardian 恢复契约，确保恢复逻辑必须显式暴露为可验证的运行期路径。
     */
    private fun invokeGuardianRecovery(): List<String> {
        val method = ProcessGuardian.javaClass.methods.firstOrNull { it.name == "recoverUnhealthyTaskers" }
        assertNotNull(method, "ProcessGuardian should expose recoverUnhealthyTaskers for managed recovery")
        @Suppress("UNCHECKED_CAST")
        return method.invoke(ProcessGuardian) as List<String>
    }

    @Test
    fun `process guardian should restart unhealthy managed taskers`() = runBlocking {
        val tasker = RecoverableGuardianTasker()

        tasker.start()

        withTimeout(1_000) {
            while (true) {
                val snapshot = tasker.healthSnapshot()
                if (snapshot.workerSnapshots.isNotEmpty() && snapshot.workerSnapshots.single().restartExhausted) {
                    break
                }
                delay(10)
            }
        }

        val recoveredTaskers = invokeGuardianRecovery()
        assertTrue(recoveredTaskers.contains("ListenerTasker"))

        withTimeout(1_000) {
            tasker.recovered.await()
        }

        assertTrue(tasker.healthSnapshot().healthy)

        tasker.cancel()
    }
}
