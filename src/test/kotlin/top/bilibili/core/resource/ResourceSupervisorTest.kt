package top.bilibili.core.resource

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceSupervisorTest {
    @Test
    fun `stopAll should continue when one partition fails`() = runBlocking {
        val supervisor = ResourceSupervisor()
        val trace = mutableListOf<String>()

        supervisor.register(
            LambdaResourcePartition(
                id = "ok-1",
                stopAction = { trace += "ok-1" },
            )
        )
        supervisor.register(
            LambdaResourcePartition(
                id = "boom",
                stopAction = {
                    trace += "boom"
                    error("partition failed")
                },
            )
        )
        supervisor.register(
            LambdaResourcePartition(
                id = "ok-2",
                stopAction = { trace += "ok-2" },
            )
        )

        val report = supervisor.stopAll()

        assertEquals(listOf("ok-2", "boom", "ok-1"), trace, "stop should run in reverse registration order")
        assertEquals(3, report.totalPartitions)
        assertEquals(2, report.stoppedPartitions)
        assertEquals(1, report.failedPartitions)
        assertFalse(report.success)
        assertTrue(report.failures.any { it.partitionId == "boom" })
    }

    @Test
    fun `stopAll should be idempotent`() = runBlocking {
        val supervisor = ResourceSupervisor()
        var count = 0
        supervisor.register(
            LambdaResourcePartition(
                id = "only",
                stopAction = { count++ },
            )
        )

        val report1 = supervisor.stopAll()
        val report2 = supervisor.stopAll()

        assertTrue(report1.success)
        assertTrue(report2.success)
        assertEquals(1, count)
    }
}

