package top.bilibili.core.resource

import kotlin.test.Test
import kotlin.test.assertFailsWith

class TaskResourcePolicyRegistryTest {
    @Test
    fun `validateCoverage passes when all startup tasks are covered`() {
        val tasks = listOf(
            "ListenerTasker",
            "DynamicCheckTasker",
            "LiveCheckTasker",
            "LiveCloseCheckTasker",
            "DynamicMessageTasker",
            "LiveMessageTasker",
            "SendTasker",
            "CacheClearTasker",
            "LogClearTasker",
            "SkiaCleanupTasker",
            "ProcessGuardian",
        )
        TaskResourcePolicyRegistry.validateCoverage(tasks)
    }

    @Test
    fun `validateCoverage fails when startup task is missing policy`() {
        assertFailsWith<IllegalArgumentException> {
            TaskResourcePolicyRegistry.validateCoverage(listOf("UnknownTasker"))
        }
    }
}

