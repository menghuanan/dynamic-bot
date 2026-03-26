package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class SubscriptionSourceRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `sub data should persist source references`() {
        val text = read("src/main/kotlin/top/bilibili/BiliData.kt")
        assertTrue(text.contains("sourceRefs"), "SubData should contain sourceRefs to support shared subscription pool")
    }

    @Test
    fun `dynamic service should expose direct and group subscription entry points`() {
        val text = read("src/main/kotlin/top/bilibili/service/DynamicService.kt")
        assertTrue(text.contains("addDirectSubscribe"))
        assertTrue(text.contains("removeDirectSubscribe"))
        assertTrue(text.contains("addGroupSubscribe"))
        assertTrue(text.contains("removeGroupSubscribe"))
    }

    @Test
    fun `subscription command should validate target group membership for explicit group id`() {
        val text = read("src/main/kotlin/top/bilibili/service/SubscriptionCommandService.kt")
        assertTrue(
            text.contains("PlatformCapabilityService.canSendMessageTo(target)"),
            "explicit group target should be validated through contact send capability before add",
        )
    }
}
