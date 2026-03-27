package top.bilibili.connector

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CapabilityGuardTest {
    // 使用最小假适配器覆盖 guard 分支，确保统一 capability 结果不会依赖具体平台实现。
    private class FakePlatformAdapter(
        private val capabilities: Set<PlatformCapability>,
        private val canSend: Boolean = true,
        private val canSendImagesNow: Boolean = true,
        private val canReplyNow: Boolean = true,
        private val canAtAllNow: Boolean = true,
    ) : PlatformAdapter {
        override val eventFlow: Flow<PlatformInboundMessage> = emptyFlow()

        override fun start() = Unit

        override fun stop() = Unit

        override suspend fun sendMessage(contact: PlatformContact, message: List<OutgoingPart>): Boolean = true

        override fun runtimeStatus(): PlatformRuntimeStatus = PlatformRuntimeStatus(connected = true, reconnectAttempts = 0)

        override suspend fun isContactReachable(contact: PlatformContact): Boolean = canSend

        override suspend fun canSendMessage(contact: PlatformContact): Boolean = canSend

        override suspend fun canSendImages(contact: PlatformContact, images: List<ImageSource>): Boolean = canSendImagesNow

        override suspend fun canReply(contact: PlatformContact): Boolean = canReplyNow

        override suspend fun canAtAll(contact: PlatformContact): Boolean = canAtAllNow

        override fun declaredCapabilities(): Set<PlatformCapability> = capabilities
    }

    private val groupContact = PlatformContact(
        platform = PlatformType.ONEBOT11,
        type = PlatformChatType.GROUP,
        id = "100",
    )

    // 约束显式声明且当前可用的能力必须返回 Supported，避免 guard 自己制造假降级。
    @Test
    fun `guard should return supported when declared capability is currently available`() = runBlocking {
        val adapter = FakePlatformAdapter(capabilities = setOf(PlatformCapability.SEND_MESSAGE))

        val result = CapabilityGuard.evaluate(
            adapter = adapter,
            request = CapabilityRequest(
                capability = PlatformCapability.SEND_MESSAGE,
                contact = groupContact,
            ),
        )

        assertIs<CapabilityGuardResult.Supported>(result)
        assertFalse(result.stopCurrentOperation)
    }

    // 约束未声明的能力必须直接返回 Unsupported，并带出统一提示语标记。
    @Test
    fun `guard should mark undeclared capability as unsupported and stop only current operation`() = runBlocking {
        val adapter = FakePlatformAdapter(capabilities = emptySet())

        val result = CapabilityGuard.evaluate(
            adapter = adapter,
            request = CapabilityRequest(
                capability = PlatformCapability.SEND_IMAGES,
                contact = groupContact,
                images = listOf(ImageSource.RemoteUrl("https://example.com/a.png")),
            ),
        )

        val unsupported = assertIs<CapabilityGuardResult.Unsupported>(result)
        assertTrue(unsupported.stopCurrentOperation)
        assertFalse(unsupported.allowFallback)
        assertEquals(CapabilityGuard.UNSUPPORTED_MESSAGE, unsupported.marker)
    }

    // 约束已声明但当前不可用的能力必须返回 Degraded，供后续任务只执行显式 fallback 路径。
    @Test
    fun `guard should degrade when declared capability is temporarily unavailable`() = runBlocking {
        val adapter = FakePlatformAdapter(
            capabilities = setOf(PlatformCapability.REPLY),
            canReplyNow = false,
        )

        val result = CapabilityGuard.evaluate(
            adapter = adapter,
            request = CapabilityRequest(
                capability = PlatformCapability.REPLY,
                contact = groupContact,
            ),
        )

        val degraded = assertIs<CapabilityGuardResult.Degraded>(result)
        assertTrue(degraded.stopCurrentOperation)
        assertTrue(degraded.allowFallback)
        assertEquals(CapabilityGuard.UNSUPPORTED_MESSAGE, degraded.marker)
    }
}
