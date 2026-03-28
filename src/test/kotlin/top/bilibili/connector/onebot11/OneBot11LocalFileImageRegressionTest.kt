package top.bilibili.connector.onebot11

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import top.bilibili.connector.ImageSource
import top.bilibili.connector.OutgoingPart
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformRuntimeStatus
import top.bilibili.connector.PlatformType
import top.bilibili.connector.onebot11.core.OneBot11MessageEvent
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment
import top.bilibili.connector.onebot11.core.OneBot11Transport
import java.nio.file.Files
import kotlin.io.path.createTempFile
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OneBot11LocalFileImageRegressionTest {
    // 最小 transport 桩只记录最终消息段，便于断言适配器是否承担了本地图编码职责。
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
            lastSentMessage = message
            return true
        }

        override fun runtimeStatus(): PlatformRuntimeStatus = PlatformRuntimeStatus(connected = true, reconnectAttempts = 0)
    }

    @Test
    fun `onebot11 adapter should keep local image files as file uri payloads`() = runBlocking {
        val tempImage = createTempFile(prefix = "onebot11-local-file-", suffix = ".png")
        val transport = FakeTransport()
        val adapter = OneBot11Adapter(transport)
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")

        try {
            // 使用真实文件字节驱动发送，确保测试约束的是适配器行为而不是 source 文本片段。
            tempImage.writeBytes(byteArrayOf(1, 2, 3, 4))

            assertTrue(
                adapter.sendMessage(
                    contact,
                    listOf(OutgoingPart.image(ImageSource.LocalFile(tempImage.toString()))),
                ),
            )

            val imageSegment = transport.lastSentMessage.single()
            val filePayload = imageSegment.data.getValue("file")
            assertEquals("image", imageSegment.type)
            assertTrue(
                filePayload.startsWith("file:///"),
                "local image files should stay as file URIs until the vendor client decides whether sendMode requires base64",
            )
            assertFalse(
                filePayload.startsWith("base64://"),
                "generic adapter should not eagerly convert local image files into base64 payloads",
            )
        } finally {
            // 删除临时文件，避免测试运行后污染仓库外部 temp 目录。
            Files.deleteIfExists(tempImage)
        }
    }
}
