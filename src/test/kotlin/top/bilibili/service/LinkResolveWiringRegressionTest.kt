package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkResolveWiringRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)
    private fun readNormalized(path: String): String = read(path).replace("\r\n", "\n")

    @Test
    fun `link resolve path should gate drawing and keep text fallback`() {
        val resolve = read("src/main/kotlin/top/bilibili/service/ResolveLinkService.kt")
        val listener = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")
        val dispatch = read("src/main/kotlin/top/bilibili/service/MessageEventDispatchService.kt")

        assertTrue(resolve.contains("FeatureSwitchService.canRenderLinkResolveDraw"))
        assertTrue(listener.contains("OutgoingPart.text(standardLink)"))
        assertTrue(listener.contains("LinkResolvePolicyService"), "listener should delegate link approval to policy service")
        assertTrue(listener.contains("PlatformCapability.LINK_RESOLVE"), "listener should guard link resolve by explicit capability")
        assertTrue(listener.contains("CapabilityRequest("), "listener should build a capability request for link resolve")
        assertTrue(listener.contains("stopCurrentOperation"), "listener should stop only the current event path when guard blocks link resolve")
        assertTrue(dispatch.contains("guardMessageSend"), "event dispatch should stop current event when the platform cannot reply")
        assertTrue(
            listener.contains("TOO_MANY_REQUESTS_NOTICE"),
            "listener should emit the configured throttle warning text",
        )
        assertFalse(listener.contains("recentlyParsedLinks"), "listener should no longer keep local cooldown cache state")
        assertFalse(listener.contains("legacyLinkCooldownCache"), "listener should not keep renamed legacy cooldown cache state")
        assertFalse(listener.contains("MAX_CACHE_SIZE"), "listener should not keep cache-size tuning for link cooldown state")
        assertFalse(listener.contains("cacheDuration"), "listener should not keep local cooldown duration state")
        assertFalse(listener.contains("cacheMutex"), "listener should not synchronize its own cooldown cache anymore")
        assertFalse(listener.contains("cleanExpiredCache"), "listener should not own cooldown cleanup routines")
        assertFalse(listener.contains("extractMiniAppUrl("), "listener should not parse OneBot11 mini-app payloads locally anymore")
    }

    @Test
    fun `resolve link service should not keep unreachable fallback blocks after delegation`() {
        val resolve = readNormalized("src/main/kotlin/top/bilibili/service/ResolveLinkService.kt")

        assertFalse(
            resolve.contains(
                """
                return matchingAllRegularOrdered(content, subject)

                    val results = mutableListOf<ResolvedLinkInfo>()
                """.trimIndent(),
            ),
            "matchingAllRegular should not keep the old implementation after delegating to the ordered path",
        )
        assertFalse(
            resolve.contains(
                """
                return resolveLinkMatch(type, matchResult, subject)

                    val id = matchResult.destructured.component1()
                """.trimIndent(),
            ),
            "matchingInternalRegular should not keep the old implementation after delegating to resolveLinkMatch",
        )
    }
}
