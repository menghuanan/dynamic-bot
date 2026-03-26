package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformCapabilitySourceRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `platform capability service should expose business intent helpers`() {
        val source = read("src/main/kotlin/top/bilibili/connector/PlatformCapabilityService.kt")

        listOf(
            "suspend fun canSendMessageTo(",
            "suspend fun canSendManagedAdminNotice(",
            "suspend fun canSendImagesTo(",
            "suspend fun canReplyInContact(",
            "suspend fun canAtAllInContact(",
        ).forEach { marker ->
            assertTrue(source.contains(marker), "PlatformCapabilityService should expose $marker")
        }
    }

    @Test
    fun `admin notice helper should route through platform capability service`() {
        val source = read("src/main/kotlin/top/bilibili/utils/General.kt")

        assertTrue(source.contains("PlatformCapabilityService.canSendManagedAdminNotice"))
        assertFalse(source.contains("FeatureSwitchService.canSendManagedAdminNotice()"))
    }

    @Test
    fun `legacy numeric send helpers should be marked as compatibility only`() {
        val adapterSource = read("src/main/kotlin/top/bilibili/connector/PlatformAdapter.kt")
        val gatewaySource = read("src/main/kotlin/top/bilibili/service/MessageGateway.kt")
        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")

        listOf(adapterSource, gatewaySource, botSource).forEach { source ->
            assertTrue(source.contains("@Deprecated"), "legacy numeric send helpers should be deprecated")
        }
    }

    @Test
    fun `subscription command should validate explicit target through contact capability`() {
        val source = read("src/main/kotlin/top/bilibili/service/SubscriptionCommandService.kt")

        assertTrue(source.contains("PlatformCapabilityService.canSendMessageTo(target)"))
        assertFalse(source.contains("PlatformCapabilityService.isContactReachable(target)"))
    }
}
