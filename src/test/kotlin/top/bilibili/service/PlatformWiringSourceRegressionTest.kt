package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlatformWiringSourceRegressionTest {
    // 统一按 UTF-8 读取源码，避免 source regression 受宿主默认编码影响。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `login service should no longer send through BiliBiliBot napCat directly`() {
        val source = read("src/main/kotlin/top/bilibili/service/LoginService.kt")

        assertFalse(source.contains("BiliBiliBot.napCat.sendGroupMessage"))
        assertFalse(source.contains("BiliBiliBot.napCat.sendPrivateMessage"))
    }

    @Test
    fun `listener tasker should consume platform adapter flow`() {
        val source = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")

        assertFalse(source.contains("BiliBiliBot.napCat.eventFlow"))
        // 监听入口必须消费 connector manager 暴露的事件流，而不是从 raw adapter 直接取流。
        assertFalse(source.contains("requirePlatformAdapter().eventFlow"))
        assertTrue(source.contains("requireConnectorManager().eventFlow") || source.contains("platformConnectorManager.eventFlow"))
    }

    @Test
    fun `bot startup should select adapter from platform config and start through connector manager`() {
        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")
        val configSource = read("src/main/kotlin/top/bilibili/config/NapCatConfig.kt")
        val configManagerSource = read("src/main/kotlin/top/bilibili/config/ConfigManager.kt")
        val connectorManagerSource = read("src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt")

        assertTrue(botSource.contains("requireConnectorManager().start()"))
        assertFalse(botSource.contains("platformAdapter.start()"))
        assertFalse(botSource.contains("napCat.start()"))
        assertTrue(connectorManagerSource.contains("selectedAdapterKind"))
        assertTrue(configSource.contains("val platform"))
        assertTrue(configSource.contains("qqOfficial"))
        assertTrue(configManagerSource.contains("selectedPlatformType"))
    }

    // 约束启动选择必须显式区分 NapCat 与通用 OneBot11，避免继续把供应商实现藏在协议类型后面。
    @Test
    fun `bot startup should distinguish napcat from generic onebot11 adapter selection`() {
        val modelSource = read("src/main/kotlin/top/bilibili/connector/PlatformModels.kt")
        val configSource = read("src/main/kotlin/top/bilibili/config/NapCatConfig.kt")
        val connectorManagerSource = read("src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt")

        assertTrue(modelSource.contains("enum class PlatformAdapterKind"))
        assertTrue(modelSource.contains("NAPCAT"))
        assertTrue(modelSource.contains("ONEBOT11"))
        assertTrue(configSource.contains("selectedAdapterKind"))
        assertTrue(connectorManagerSource.contains("config.selectedAdapterKind()"))
        assertTrue(connectorManagerSource.contains("PlatformAdapterKind.NAPCAT"))
        assertTrue(connectorManagerSource.contains("PlatformAdapterKind.ONEBOT11"))
    }

    // 约束能力判断必须统一收口到 capability guard，而不是继续散落在 service 里拼布尔判断。
    @Test
    fun `platform capability service should route through unified capability guard`() {
        val adapterSource = read("src/main/kotlin/top/bilibili/connector/PlatformAdapter.kt")
        val serviceSource = read("src/main/kotlin/top/bilibili/connector/PlatformCapabilityService.kt")
        val guardianSource = read("src/main/kotlin/top/bilibili/tasker/ProcessGuardian.kt")
        val gatewaySource = read("src/main/kotlin/top/bilibili/service/DefaultMessageGateway.kt")

        assertTrue(adapterSource.contains("declaredCapabilities"))
        assertTrue(adapterSource.contains("guardCapability"))
        assertTrue(serviceSource.contains("CapabilityGuard"))
        assertTrue(serviceSource.contains("CapabilityRequest"))
        assertTrue(serviceSource.contains("guardMessageSend"))
        // 能力判断与运行状态查询都必须通过 connector manager，避免回退到 BiliBiliBot 暴露 raw adapter。
        assertFalse(serviceSource.contains("BiliBiliBot.platformAdapter"))
        assertFalse(guardianSource.contains("BiliBiliBot.platformAdapter"))
        // 消息网关应依赖 connector 自己的发送入口，而不是注入 raw adapter provider。
        assertFalse(gatewaySource.contains("PlatformAdapter"))
        assertFalse(gatewaySource.contains("platformAdapterProvider"))
    }

    @Test
    fun `main ingress should only rely on neutral inbound fields`() {
        val dispatchSource = read("src/main/kotlin/top/bilibili/service/MessageEventDispatchService.kt")
        val routerSource = read("src/main/kotlin/top/bilibili/service/MessageCommandRouterService.kt")

        listOf("event.messageType", "event.groupId", "event.rawMessage", "event.userId").forEach { legacy ->
            assertFalse(dispatchSource.contains(legacy), "dispatch should not read legacy field $legacy")
            assertFalse(routerSource.contains(legacy), "router should not read legacy field $legacy")
        }
        assertTrue(dispatchSource.contains("chatType"))
        assertTrue(routerSource.contains("messageText"))
    }

    @Test
    fun `listener should consume normalized search texts instead of raw onebot segments`() {
        val listenerSource = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")

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
            val source = read(path)
            assertFalse(source.contains("BiliBiliBot.napCat"), "Direct napCat usage should be removed from $path")
        }
    }

    // 约束启动层只依赖 connector manager，避免 Bot 再次回退到直接 new vendor adapter 的模式。
    @Test
    fun `bot startup should delegate platform lifecycle to connector manager`() {
        val botSource = read("src/main/kotlin/top/bilibili/core/BiliBiliBot.kt")

        assertTrue(botSource.contains("PlatformConnectorManager"), "bot startup should depend on PlatformConnectorManager")
        assertFalse(botSource.contains("NapCatClient("), "bot startup should not construct NapCatClient directly")
        assertFalse(botSource.contains("NapCatAdapter("), "bot startup should not construct NapCatAdapter directly")
        assertFalse(botSource.contains("GenericOneBot11Adapter("), "bot startup should not construct GenericOneBot11Adapter directly")
        assertFalse(botSource.contains("QQOfficialAdapter("), "bot startup should not construct QQOfficialAdapter directly")
    }
}
