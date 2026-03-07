package top.bilibili.tasker

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

class BiliTaskerRegressionTest {
    private class DummyTasker(name: String) : BiliTasker(name) {
        override var interval: Int = -1
        override suspend fun main() {}
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
}
