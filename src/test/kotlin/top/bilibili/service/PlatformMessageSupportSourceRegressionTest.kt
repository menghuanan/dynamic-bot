package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformMessageSupportSourceRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `platform message support should expose capability aware image fallback helper`() {
        val source = read("src/main/kotlin/top/bilibili/service/PlatformMessageSupport.kt")

        assertTrue(source.contains("suspend fun sendPartsWithCapabilityFallback("))
        assertTrue(source.contains("PlatformCapabilityService.guardImageSend"))
        assertTrue(source.contains("CapabilityGuardResult.Degraded"))
        assertTrue(source.contains("fallbackText"))
    }

    @Test
    fun `image heavy business flows should route through capability aware helper`() {
        val loginSource = read("src/main/kotlin/top/bilibili/service/LoginService.kt")
        val helpSource = read("src/main/kotlin/top/bilibili/service/PresentationCommandService.kt")
        val listenerSource = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")

        assertTrue(loginSource.contains("sendPartsWithCapabilityFallback("))
        assertTrue(helpSource.contains("sendPartsWithCapabilityFallback("))
        assertTrue(listenerSource.contains("sendPartsWithCapabilityFallback("))
    }
}
