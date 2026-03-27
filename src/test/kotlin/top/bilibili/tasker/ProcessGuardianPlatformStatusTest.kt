package top.bilibili.tasker

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProcessGuardianPlatformStatusTest {
    // 统一按 UTF-8 读取源码，避免 source regression 在 Windows 上受默认编码影响。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `platform runtime status should expose neutral inbound and outbound pressure fields`() {
        val source = read("src/main/kotlin/top/bilibili/connector/PlatformModels.kt")

        assertTrue(source.contains("inboundPressureActive"))
        assertTrue(source.contains("inboundDroppedEvents"))
        assertTrue(source.contains("outboundPressureActive"))
        assertTrue(source.contains("outboundDroppedEvents"))
        assertFalse(source.contains("sendQueueFull"))
    }

    @Test
    fun `process guardian should read neutral platform pressure fields instead of napcat queue semantics`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")

        assertTrue(source.contains("outboundPressureActive") || source.contains("inboundPressureActive"))
        assertFalse(source.contains("sendQueueFull"))
    }
}
