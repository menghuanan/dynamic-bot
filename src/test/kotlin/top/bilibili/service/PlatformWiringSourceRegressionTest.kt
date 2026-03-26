package top.bilibili.service

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformWiringSourceRegressionTest {
    @Test
    fun `login service should no longer send through BiliBiliBot napCat directly`() {
        val source = File("src/main/kotlin/top/bilibili/service/LoginService.kt").readText()

        assertFalse(source.contains("BiliBiliBot.napCat.sendGroupMessage"))
        assertFalse(source.contains("BiliBiliBot.napCat.sendPrivateMessage"))
    }

    @Test
    fun `listener tasker should consume platform adapter flow`() {
        val source = File("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt").readText()

        assertFalse(source.contains("BiliBiliBot.napCat.eventFlow"))
        assertTrue(source.contains("platformAdapter.eventFlow") || source.contains("requirePlatformAdapter().eventFlow"))
    }

    @Test
    fun `bot startup should select adapter from platform config and start through adapter`() {
        val botSource = File("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt").readText()
        val configSource = File("src/main/kotlin/top/bilibili/config/NapCatConfig.kt").readText()
        val managerSource = File("src/main/kotlin/top/bilibili/config/ConfigManager.kt").readText()

        assertTrue(botSource.contains("platformAdapter.start()"))
        assertFalse(botSource.contains("napCat.start()"))
        assertTrue(configSource.contains("val platform"))
        assertTrue(configSource.contains("qqOfficial"))
        assertTrue(managerSource.contains("selectedPlatformType"))
    }

    // 约束启动选择必须显式区分 NapCat 与通用 OneBot11，避免继续把供应商实现藏在协议类型后面。
    @Test
    fun `bot startup should distinguish napcat from generic onebot11 adapter selection`() {
        val modelSource = File("src/main/kotlin/top/bilibili/connector/PlatformModels.kt").readText()
        val configSource = File("src/main/kotlin/top/bilibili/config/NapCatConfig.kt").readText()
        val botSource = File("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt").readText()

        assertTrue(modelSource.contains("enum class PlatformAdapterKind"))
        assertTrue(modelSource.contains("NAPCAT"))
        assertTrue(modelSource.contains("ONEBOT11"))
        assertTrue(configSource.contains("selectedAdapterKind"))
        assertTrue(botSource.contains("config.selectedAdapterKind()"))
        assertTrue(botSource.contains("PlatformAdapterKind.NAPCAT"))
        assertTrue(botSource.contains("PlatformAdapterKind.ONEBOT11"))
    }

    // 约束能力判断必须统一收口到 capability guard，而不是继续散落在 service 里拼布尔判断。
    @Test
    fun `platform capability service should route through unified capability guard`() {
        val adapterSource = File("src/main/kotlin/top/bilibili/connector/PlatformAdapter.kt").readText()
        val serviceSource = File("src/main/kotlin/top/bilibili/connector/PlatformCapabilityService.kt").readText()

        assertTrue(adapterSource.contains("declaredCapabilities"))
        assertTrue(adapterSource.contains("guardCapability"))
        assertTrue(serviceSource.contains("CapabilityGuard"))
        assertTrue(serviceSource.contains("CapabilityRequest"))
        assertTrue(serviceSource.contains("guardMessageSend"))
    }

    @Test
    fun `main ingress should only rely on neutral inbound fields`() {
        val dispatchSource = File("src/main/kotlin/top/bilibili/service/MessageEventDispatchService.kt").readText()
        val routerSource = File("src/main/kotlin/top/bilibili/service/MessageCommandRouterService.kt").readText()

        listOf("event.messageType", "event.groupId", "event.rawMessage", "event.userId").forEach { legacy ->
            assertFalse(dispatchSource.contains(legacy), "dispatch should not read legacy field $legacy")
            assertFalse(routerSource.contains(legacy), "router should not read legacy field $legacy")
        }
        assertTrue(dispatchSource.contains("chatType"))
        assertTrue(routerSource.contains("messageText"))
    }

    @Test
    fun `listener should consume normalized search texts instead of raw onebot segments`() {
        val listenerSource = File("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt").readText()

        listOf("event.messageType", "event.groupId", "event.message", "extractMiniAppUrl").forEach { legacy ->
            assertFalse(listenerSource.contains(legacy), "listener should not depend on legacy OneBot detail $legacy")
        }
        assertTrue(listenerSource.contains("searchTexts"))
        assertTrue(listenerSource.contains("hasMention"))
    }

    @Test
    fun `business services should no longer call BiliBiliBot napCat directly for capabilities`() {
        val targets = listOf(
            "src/main/kotlin/top/bilibili/service/GroupCommandService.kt",
            "src/main/kotlin/top/bilibili/service/SettingsCommandService.kt",
            "src/main/kotlin/top/bilibili/service/SubscriptionCommandService.kt",
            "src/main/kotlin/top/bilibili/tasker/SendTasker.kt",
            "src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt",
        )

        targets.forEach { path ->
            val source = File(path).readText()
            assertFalse(source.contains("BiliBiliBot.napCat"), "Direct napCat usage should be removed from $path")
        }
    }
}
