package top.bilibili.connector.onebot11.vendors.llbot

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import top.bilibili.config.NapCatConfig
import top.bilibili.connector.CapabilityGuardResult
import top.bilibili.connector.CapabilityRequest
import top.bilibili.connector.PlatformCapability
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType

class LlBotAdapterTest {
    @Test
    fun `llbot adapter should declare atall and degrade when runtime probe is unavailable`() = runBlocking {
        val adapter = LlBotAdapter(LlBotClient(NapCatConfig()))
        val contact = PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "100")

        val guard = adapter.guardCapability(
            CapabilityRequest(
                capability = PlatformCapability.AT_ALL,
                contact = contact,
            ),
        )

        assertIs<CapabilityGuardResult.Degraded>(guard)
    }

    @Test
    fun `llbot adapter should treat disconnected group as unreachable`() = runBlocking {
        val adapter = LlBotAdapter(LlBotClient(NapCatConfig()))

        assertFalse(adapter.isGroupReachable(100L))
    }
}
