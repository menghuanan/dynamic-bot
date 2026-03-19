package top.bilibili.core.resource

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ResourceSupervisorTest {
    @Test
    fun `stopAll should honor shutdown phases before reverse registration order`() = runBlocking {
        val supervisor = ResourceSupervisor()
        val trace = mutableListOf<String>()

        supervisor.register(
            LambdaResourcePartition(
                id = "dependencies",
                shutdownPhase = ShutdownPhase.DEPENDENCIES,
                stopAction = { trace += "dependencies" },
            )
        )
        supervisor.register(
            LambdaResourcePartition(
                id = "worker-a",
                shutdownPhase = ShutdownPhase.WORKERS,
                stopAction = { trace += "worker-a" },
            )
        )
        supervisor.register(
            LambdaResourcePartition(
                id = "channels",
                shutdownPhase = ShutdownPhase.CHANNELS,
                stopAction = { trace += "channels" },
            )
        )
        supervisor.register(
            LambdaResourcePartition(
                id = "worker-b",
                shutdownPhase = ShutdownPhase.WORKERS,
                stopAction = { trace += "worker-b" },
            )
        )
        supervisor.register(
            LambdaResourcePartition(
                id = "ingress",
                shutdownPhase = ShutdownPhase.INGRESS,
                stopAction = { trace += "ingress" },
            )
        )
        supervisor.register(
            LambdaResourcePartition(
                id = "root",
                shutdownPhase = ShutdownPhase.ROOT_SCOPE,
                stopAction = { trace += "root" },
            )
        )

        val report = supervisor.stopAll()

        assertTrue(report.success)
        assertEquals(
            listOf("ingress", "worker-b", "worker-a", "channels", "dependencies", "root"),
            trace,
            "stop should honor phase ordering and reverse registration inside each phase",
        )
        assertEquals(
            listOf(
                ShutdownPhase.INGRESS,
                ShutdownPhase.WORKERS,
                ShutdownPhase.CHANNELS,
                ShutdownPhase.DEPENDENCIES,
                ShutdownPhase.ROOT_SCOPE,
            ),
            report.phaseReports.map { it.phase },
        )
    }

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

