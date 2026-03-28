package top.bilibili.connector.onebot11

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes
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
        var lastSentMessage: List<OneBot11MessageSegment> = emptyList()

        override fun start() = Unit

        override suspend fun stop() = Unit

        override suspend fun sendMessage(
            chatType: PlatformChatType,
            targetId: Long,
            message: List<OneBot11MessageSegment>,
        ): Boolean {
            // 记录协议层最终消息段，便于验证适配器是否已把图片转换为可直接发送的 payload。
            lastSentMessage = message
            return true
        }

        override fun runtimeStatus(): PlatformRuntimeStatus = PlatformRuntimeStatus(connected = true, reconnectAttempts = 0)
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

    @Test
    fun `image only cq raw payload should not be forwarded into searchable texts`() {
        val normalized = OneBot11Adapter.normalize(
            OneBot11MessageEvent(
                messageType = "group",
                messageId = 2,
                userId = 201L,
                message = listOf(
                    OneBot11MessageSegment(
                        "image",
                        mapOf(
                            "file" to "0ACE5C07EAF2D150E305AE5D59F46BF6.jpg",
                            "url" to "https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=demo",
                        ),
                    ),
                ),
                rawMessage = "[CQ:image,file=0ACE5C07EAF2D150E305AE5D59F46BF6.jpg,subType=1,url=https://multimedia.nt.qq.com.cn/download?appid=1407&fileid=demo,file_size=1283]",
                groupId = 101L,
                selfId = 300L,
            ),
        )

        assertTrue(normalized.searchTexts.isEmpty(), "image-only CQ payload should not leak into link resolution search texts")
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

    @Test
    fun `generic onebot11 shutdown should use suspend safe transport stop path`() {
        val transportSource = read("src/main/kotlin/top/bilibili/connector/onebot11/core/KtorOneBot11Transport.kt")
        val managerSource = read("src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt")

        // generic transport 停机不得再用 runBlocking 桥接同步关闭，必须由上层 suspend 生命周期调用。
        assertFalse(transportSource.contains("runBlocking"))
        assertTrue(managerSource.contains("suspend fun stop()"))
    }

    @Test
    fun `llbot vendor adapter should exist separately from generic and napcat`() {
        val managerSource = read("src/main/kotlin/top/bilibili/connector/PlatformConnectorManager.kt")
        val llBotAdapterFile = File("src/main/kotlin/top/bilibili/connector/onebot11/vendors/llbot/LlBotAdapter.kt")
        val llBotClientFile = File("src/main/kotlin/top/bilibili/connector/onebot11/vendors/llbot/LlBotClient.kt")

        assertTrue(llBotAdapterFile.exists(), "llbot adapter should exist under the llbot vendor directory")
        assertTrue(llBotClientFile.exists(), "llbot client should exist under the llbot vendor directory")
        assertTrue(managerSource.contains("LlBotAdapter("))
        assertTrue(managerSource.contains("PlatformAdapterKind.LLBOT"))
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

    @Test
    fun `binary image payload should be normalized into base64 onebot segment`() = runBlocking {
        val transport = FakeTransport()
        val adapter = OneBot11Adapter(transport)
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")

        assertTrue(
            adapter.sendMessage(
                contact,
                listOf(
                    top.bilibili.connector.OutgoingPart.image(
                        ImageSource.Binary(byteArrayOf(1, 2, 3, 4), "login.png"),
                    ),
                ),
            ),
        )

        val imageSegment = transport.lastSentMessage.single()
        assertEquals("image", imageSegment.type)
        assertTrue(
            imageSegment.data.getValue("file").startsWith("base64://"),
            "binary image payload should bypass temp file reopening and become base64 directly",
        )
    }

    @Test
    fun `local file and remote url images should keep adapter side transport ownership`() = runBlocking {
        val tempImage = createTempFile(prefix = "onebot11-adapter-", suffix = ".png")
        val transport = FakeTransport()
        val adapter = OneBot11Adapter(transport)
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")

        try {
            // 用真实本地文件驱动消息段转换，确保测试覆盖的是 adapter 行为而不是字符串拼接。
            tempImage.writeBytes(byteArrayOf(9, 8, 7, 6))

            assertTrue(
                adapter.sendMessage(
                    contact,
                    listOf(
                        top.bilibili.connector.OutgoingPart.image(ImageSource.LocalFile(tempImage.toString())),
                        top.bilibili.connector.OutgoingPart.image(ImageSource.RemoteUrl("https://example.com/cover.png")),
                    ),
                ),
            )

            val localImageSegment = transport.lastSentMessage[0]
            val remoteImageSegment = transport.lastSentMessage[1]
            assertTrue(
                localImageSegment.data.getValue("file").startsWith("base64://"),
                "adapter should convert local files into base64 payloads before transport",
            )
            assertEquals(
                "https://example.com/cover.png",
                remoteImageSegment.data.getValue("file"),
                "adapter should keep remote image URLs unchanged",
            )
        } finally {
            // 测试结束后删除临时文件，避免本地图样本残留到外部 temp 目录。
            Files.deleteIfExists(tempImage)
        }
    }
}
