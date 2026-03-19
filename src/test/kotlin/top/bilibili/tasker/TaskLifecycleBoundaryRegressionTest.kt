package top.bilibili.tasker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class TaskLifecycleBoundaryRegressionTest {
    private fun read(path: String): String {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8)
    }

    @Test
    fun `tasker base should expose explicit lifecycle timing hook`() {
        val text = read("src/main/kotlin/top/bilibili/tasker/BiliTasker.kt")
        assertTrue(text.contains("protected open val wrapMainInBusinessLifecycle: Boolean = true"))
        assertTrue(text.contains("protected suspend fun <T> runBusinessOperation("))
    }

    @Test
    fun `listener and send taskers should opt out of whole-run lifecycle timing`() {
        val listener = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")
        val send = read("src/main/kotlin/top/bilibili/tasker/SendTasker.kt")

        assertTrue(listener.contains("override val wrapMainInBusinessLifecycle = false"))
        assertTrue(send.contains("override val wrapMainInBusinessLifecycle = false"))
    }

    @Test
    fun `message taskers should receive before starting explicit business timing`() {
        val dynamic = read("src/main/kotlin/top/bilibili/tasker/DynamicMessageTasker.kt")
        val live = read("src/main/kotlin/top/bilibili/tasker/LiveMessageTasker.kt")

        assertTrue(dynamic.contains("override val wrapMainInBusinessLifecycle = false"))
        assertTrue(dynamic.contains("val dynamicDetail = dynamicChannel.receive()"))
        assertTrue(dynamic.contains("runBusinessOperation("))

        assertTrue(live.contains("override val wrapMainInBusinessLifecycle = false"))
        assertTrue(live.contains("val liveDetail = liveChannel.receive()"))
        assertTrue(live.contains("runBusinessOperation("))
    }
}
