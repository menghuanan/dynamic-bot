package top.bilibili.connector.onebot11

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.ImageSource
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import top.bilibili.connector.onebot11.core.OneBot11MessageEvent
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment
import top.bilibili.connector.onebot11.core.OneBot11Transport
import top.bilibili.connector.onebot11.generic.GenericOneBot11Adapter

class OneBot11AdapterTest {
    // 统一按 UTF-8 读取源码，避免 Windows 默认编码导致 source regression 漂移。
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    // 使用最小传输桩隔离 generic adapter 的 capability 语义，避免测试退化成对 NapCat 行为的回归。
    private class FakeTransport : OneBot11Transport {
        override val eventFlow: Flow<OneBot11MessageEvent> = emptyFlow()

        override fun start() = Unit

        override fun stop() = Unit

        override suspend fun sendMessage(
            chatType: PlatformChatType,
            targetId: Long,
            message: List<OneBot11MessageSegment>,
        ): Boolean = true

        override fun runtimeStatus(): PlatformRuntimeStatus = PlatformRuntimeStatus(true, 0, false)
    }

    @Test
    fun `message event should normalize into platform inbound message`() {
        val normalized = OneBot11Adapter.normalize(
            OneBot11MessageEvent(
                messageType = "group",
                messageId = 1,
                userId = 200L,
                message = listOf(
                    OneBot11MessageSegment("at", mapOf("qq" to "300")),
                    OneBot11MessageSegment("text", mapOf("text" to "/bili list")),
                    OneBot11MessageSegment(
                        type = "json",
                        data = mapOf(
                            "data" to """{"jumpUrl":"https:\/\/www.bilibili.com\/video\/BV1xx411c7mD"}""",
                        ),
                    ),
                ),
                rawMessage = "/bili list",
                groupId = 100L,
                selfId = 300L,
            )
        )

        assertEquals(PlatformType.ONEBOT11, normalized.platform)
        assertEquals(PlatformChatType.GROUP, normalized.chatType)
        assertEquals("100", normalized.chatId)
        assertEquals("200", normalized.senderId)
        assertEquals("300", normalized.selfId)
        assertEquals("/bili list", normalized.messageText)
        assertTrue(normalized.hasMention)
        assertFalse(normalized.fromSelf)
        assertEquals(
            listOf("/bili list", "https://www.bilibili.com/video/BV1xx411c7mD"),
            normalized.searchTexts,
        )
    }

    // 约束通用 OneBot11 适配核心必须只依赖新的传输契约，避免继续把 NapCatClient 绑死在协议层。
    @Test
    fun `generic onebot11 adapter should depend on transport contract instead of napcat client`() {
        val adapterSource = read("src/main/kotlin/top/bilibili/connector/onebot11/OneBot11Adapter.kt")
        val genericAdapterFile = File("src/main/kotlin/top/bilibili/connector/onebot11/generic/GenericOneBot11Adapter.kt")
        val napCatClientFile = File("src/main/kotlin/top/bilibili/connector/onebot11/vendors/napcat/NapCatClient.kt")

        assertTrue(genericAdapterFile.exists(), "generic OneBot11 adapter should exist")
        assertTrue(napCatClientFile.exists(), "NapCat client should live under the vendor directory")
        assertTrue(adapterSource.contains("OneBot11Transport"))
        assertFalse(adapterSource.contains("NapCatClient"))
        assertTrue(read(genericAdapterFile.path).contains("OneBot11Transport"))
        assertFalse(read(genericAdapterFile.path).contains("NapCatClient"))
    }

    // 约束 generic OneBot11 的运行期 wiring 必须走独立 transport，而不是继续借道 NapCat vendor 桥接。
    @Test
    fun `generic onebot11 startup should wire through dedicated transport`() {
        val managerSource = read("src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt")
        val transportFile = File("src/main/kotlin/top/bilibili/connector/onebot11/core/KtorOneBot11Transport.kt")

        assertTrue(transportFile.exists(), "generic OneBot11 transport should exist")
        assertTrue(managerSource.contains("KtorOneBot11Transport("))
        assertFalse(managerSource.contains("GenericOneBot11Adapter(NapCatAdapter.transport("))
    }

    // 约束 generic OneBot11 要对本地图和 @全体给出显式 guard 结果，而不是继续模糊地返回发送失败。
    @Test
    fun `generic onebot11 should expose explicit unsupported reasons for local image and atall`() = runBlocking {
        val adapter = GenericOneBot11Adapter(FakeTransport())
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")

        val imageGuard = adapter.guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.SEND_IMAGES,
                contact = contact,
                images = listOf(ImageSource.LocalFile("temp/demo.png")),
            ),
        )
        val atAllGuard = adapter.guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.AT_ALL,
                contact = contact,
            ),
        )

        val degradedImage = assertIs<CapabilityGuardResult.Degraded>(imageGuard)
        assertTrue(degradedImage.reason.contains("remote image"), "generic image fallback reason should be explicit")
        val unsupportedAtAll = assertIs<CapabilityGuardResult.Unsupported>(atAllGuard)
        assertTrue(unsupportedAtAll.reason.contains("@全体"), "generic at-all reason should be explicit")
    }
}
