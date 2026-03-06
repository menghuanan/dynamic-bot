package top.bilibili.core.resource

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BusinessLifecycleManagerTest {
    @Test
    fun `cleanup runs on success in reverse order`() = runBlocking {
        val trace = mutableListOf<String>()

        BusinessLifecycleManager.run(
            owner = "test",
            operation = "success",
        ) {
            onFinally { trace += "first" }
            onFinally { trace += "second" }
            trace += "work"
        }

        assertEquals(listOf("work", "second", "first"), trace)
        assertEquals(0, BusinessLifecycleManager.activeCount())
    }

    @Test
    fun `cleanup runs on failure and active count returns to zero`() = runBlocking {
        val trace = mutableListOf<String>()

        assertFailsWith<IllegalStateException> {
            BusinessLifecycleManager.run(
                owner = "test",
                operation = "failure",
            ) {
                onFinally { trace += "cleanup" }
                error("boom")
            }
        }

        assertEquals(listOf("cleanup"), trace)
        assertEquals(0, BusinessLifecycleManager.activeCount())
    }
}

