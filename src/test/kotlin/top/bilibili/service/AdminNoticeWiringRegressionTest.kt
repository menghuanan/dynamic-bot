package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class AdminNoticeWiringRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `managed admin notice call sites should route through the switch-aware helper`() {
        val general = read("src/main/kotlin/top/bilibili/utils/General.kt")
        val sendTasker = read("src/main/kotlin/top/bilibili/tasker/SendTasker.kt")
        val cacheClear = read("src/main/kotlin/top/bilibili/tasker/CacheClearTasker.kt")

        assertTrue(general.contains("FeatureSwitchService.canSendManagedAdminNotice"))
        assertTrue(sendTasker.contains("actionNotify(") || sendTasker.contains("FeatureSwitchService.canSendManagedAdminNotice"))
        assertTrue(cacheClear.contains("actionNotify(") || cacheClear.contains("FeatureSwitchService.canSendManagedAdminNotice"))
    }
}
