package top.bilibili.service

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkResolvePolicyServiceTest {
    private class MutableClock(var now: Long = 1_000L)

    private fun link(type: LinkType, id: String) = ResolvedLinkInfo(type, id)

    @Test
    fun `cooled down link should not consume user quota`() {
        val clock = MutableClock()
        val service = LinkResolvePolicyService(nowProvider = { clock.now })

        val first = service.applyPolicy(
            groupId = 10001L,
            userId = 20001L,
            candidates = listOf(link(LinkType.VideoLink, "video-a"))
        )
        val cooled = service.applyPolicy(
            groupId = 10001L,
            userId = 20001L,
            candidates = listOf(link(LinkType.VideoLink, "video-a"))
        )
        val second = service.applyPolicy(
            groupId = 10001L,
            userId = 20001L,
            candidates = listOf(link(LinkType.Live, "live-b"))
        )
        val third = service.applyPolicy(
            groupId = 10001L,
            userId = 20001L,
            candidates = listOf(link(LinkType.Article, "article-c"))
        )

        assertEquals(listOf(link(LinkType.VideoLink, "video-a")), first.approvedLinks)
        assertFalse(first.shouldWarnTooManyRequests)
        assertTrue(cooled.approvedLinks.isEmpty(), "cooled-down duplicate should be skipped")
        assertFalse(cooled.shouldWarnTooManyRequests)
        assertEquals(listOf(link(LinkType.Live, "live-b")), second.approvedLinks)
        assertFalse(second.shouldWarnTooManyRequests)
        assertEquals(listOf(link(LinkType.Article, "article-c")), third.approvedLinks)
        assertFalse(third.shouldWarnTooManyRequests, "cooled-down duplicate must not exhaust quota")
    }

    @Test
    fun `user quota should be shared across groups`() {
        val clock = MutableClock()
        val service = LinkResolvePolicyService(nowProvider = { clock.now })

        service.applyPolicy(10001L, 20001L, listOf(link(LinkType.VideoLink, "video-a")))
        service.applyPolicy(10002L, 20001L, listOf(link(LinkType.Live, "live-b")))
        service.applyPolicy(10003L, 20001L, listOf(link(LinkType.Article, "article-c")))

        val blocked = service.applyPolicy(
            groupId = 10004L,
            userId = 20001L,
            candidates = listOf(link(LinkType.User, "user-d"))
        )

        assertTrue(blocked.approvedLinks.isEmpty())
        assertTrue(blocked.shouldWarnTooManyRequests, "fourth eligible parse should be blocked across groups")
    }

    @Test
    fun `single message should approve only first three eligible links`() {
        val clock = MutableClock()
        val service = LinkResolvePolicyService(nowProvider = { clock.now })
        val candidates = listOf(
            link(LinkType.VideoLink, "video-a"),
            link(LinkType.Live, "live-b"),
            link(LinkType.Article, "article-c"),
            link(LinkType.User, "user-d"),
        )

        val decision = service.applyPolicy(
            groupId = 10001L,
            userId = 20001L,
            candidates = candidates
        )

        assertEquals(candidates.take(3), decision.approvedLinks)
        assertFalse(decision.shouldWarnTooManyRequests, "message cap should not emit user throttle warning")
    }

    @Test
    fun `cooled down early links should allow later links to fill single message cap`() {
        val clock = MutableClock()
        val service = LinkResolvePolicyService(nowProvider = { clock.now })

        service.applyPolicy(10001L, 99999L, listOf(link(LinkType.VideoLink, "video-a")))
        service.applyPolicy(10001L, 99999L, listOf(link(LinkType.Live, "live-b")))

        val candidates = listOf(
            link(LinkType.VideoLink, "video-a"),
            link(LinkType.Live, "live-b"),
            link(LinkType.Article, "article-c"),
            link(LinkType.User, "user-d"),
            link(LinkType.Pgc, "pgc-e"),
        )

        val decision = service.applyPolicy(
            groupId = 10001L,
            userId = 20001L,
            candidates = candidates
        )

        assertEquals(candidates.drop(2), decision.approvedLinks)
        assertFalse(decision.shouldWarnTooManyRequests)
    }

    @Test
    fun `fourth eligible parse within one minute should request warning`() {
        val clock = MutableClock()
        val service = LinkResolvePolicyService(nowProvider = { clock.now })

        service.applyPolicy(10001L, 20001L, listOf(link(LinkType.VideoLink, "video-a")))
        service.applyPolicy(10001L, 20001L, listOf(link(LinkType.Live, "live-b")))
        service.applyPolicy(10001L, 20001L, listOf(link(LinkType.Article, "article-c")))

        val blocked = service.applyPolicy(
            groupId = 10001L,
            userId = 20001L,
            candidates = listOf(link(LinkType.User, "user-d"))
        )

        assertTrue(blocked.approvedLinks.isEmpty())
        assertTrue(blocked.shouldWarnTooManyRequests)
    }
}
