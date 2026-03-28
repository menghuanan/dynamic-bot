package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
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

    @Test
    fun `login service should keep transport specific image encoding out of business layer`() {
        val loginSource = read("src/main/kotlin/top/bilibili/service/LoginService.kt")

        // 登录业务层只允许产出 LocalFile 并走能力回退 helper，不应自行拼接 base64 transport payload。
        assertFalse(loginSource.contains("base64://"))
        assertFalse(loginSource.contains("Base64.getEncoder"))
        // 业务层不得直接感知 OneBot11/NapCat/llbot 等协议实现细节，避免再次破坏分层。
        assertFalse(loginSource.contains("OneBot11"))
        assertFalse(loginSource.contains("NapCat"))
        assertFalse(loginSource.contains("llbot"))
        assertTrue(loginSource.contains("sendPartsWithCapabilityFallback("))
    }
}
