package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MessageIngressRegressionTest {
    // 统一按 UTF-8 读取源码，保证 Windows 环境下源码守卫断言稳定。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `message dispatch should drop self events before routing and should not eager guard send`() {
        val dispatch = read("src/main/kotlin/top/bilibili/service/MessageEventDispatchService.kt")

        assertTrue(dispatch.contains("if (event.fromSelf)"), "dispatch should early-return self events")
        assertFalse(dispatch.contains("guardMessageSend"), "dispatch should not eager-check send capability")
        assertFalse(dispatch.contains("canReplyToCurrentEvent"), "dispatch should not keep the legacy eager reply gate helper")
    }

    @Test
    fun `listener should early return cheap cases before any send capability check`() {
        val listener = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")

        assertTrue(listener.contains("if (event.fromSelf)"), "listener should ignore self echo events before link processing")
        assertTrue(listener.contains("if (event.searchTexts.isEmpty()) return"), "listener should ignore empty search text payloads early")
        assertFalse(listener.contains("guardMessageSend(groupContact)"), "listener should not eager-check send capability before link matching")
        assertTrue(listener.contains("sendPartsWithCapabilityFallback"), "listener should keep guarded send fallback on the actual reply path")
    }
}
