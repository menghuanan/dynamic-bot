package top.bilibili.tasker

import top.bilibili.data.DynamicItem
import top.bilibili.data.LiveInfo
import top.bilibili.data.ModuleAuthor
import top.bilibili.data.ModuleDynamic
import top.bilibili.service.DrawCacheKeyService
import top.bilibili.service.PushFanoutService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PushColorSubjectScopeFeatureTest {
    @Test
    fun `dynamic detail fan-out should preserve per-contact context`() {
        val item = DynamicItem(
            typeStr = "DYNAMIC_TYPE_WORD",
            basic = DynamicItem.DynamicBasic(
                commentIdStr = "100",
                commentType = 11,
                ridStr = "200"
            ),
            idStr = "300",
            modules = DynamicItem.Modules(
                moduleAuthor = ModuleAuthor(
                    mid = 123L,
                    name = "up",
                    face = "https://example.com/avatar.png"
                ),
                moduleDynamic = ModuleDynamic()
            )
        )

        val contacts = linkedSetOf("group:10001", "group:10002")
        val details = PushFanoutService.dynamicDetailsForContacts(item, contacts)

        assertEquals(2, details.size)
        assertEquals(contacts, details.mapNotNull { it.contact }.toSet())
        assertTrue(details.all { it.item == item })
    }

    @Test
    fun `live detail fan-out should preserve per-contact context`() {
        val live = LiveInfo(
            title = "live",
            roomId = 101L,
            uid = 123L,
            uname = "up",
            face = "https://example.com/avatar.png",
            cover = "https://example.com/cover.png",
            liveTimeDuration = 1710000000L,
            liveStatus = 1,
            area = "game"
        )

        val contacts = linkedSetOf("group:10001", "group:10002", "private:20001")
        val details = PushFanoutService.liveDetailsForContacts(live, contacts)

        assertEquals(3, details.size)
        assertEquals(contacts, details.mapNotNull { it.contact }.toSet())
        assertTrue(details.all { it.item == live })
    }

    @Test
    fun `draw cache key should be stable and isolated by subject and color`() {
        val dynamicA = DrawCacheKeyService.dynamicPath(
            uid = 123L,
            dynamicId = "300",
            subject = "group:10001",
            color = "#d3edfa"
        )
        val dynamicASame = DrawCacheKeyService.dynamicPath(
            uid = 123L,
            dynamicId = "300",
            subject = "group:10001",
            color = "#d3edfa"
        )
        val dynamicB = DrawCacheKeyService.dynamicPath(
            uid = 123L,
            dynamicId = "300",
            subject = "group:10002",
            color = "#d3edfa"
        )
        val dynamicC = DrawCacheKeyService.dynamicPath(
            uid = 123L,
            dynamicId = "300",
            subject = "group:10001",
            color = "#fde8ed"
        )

        assertEquals(dynamicA, dynamicASame)
        assertNotEquals(dynamicA, dynamicB)
        assertNotEquals(dynamicA, dynamicC)

        val liveA = DrawCacheKeyService.livePath(
            uid = 123L,
            liveTime = 1710000000L,
            subject = "group:10001",
            color = "#d3edfa"
        )
        val liveB = DrawCacheKeyService.livePath(
            uid = 123L,
            liveTime = 1710000000L,
            subject = "group:10002",
            color = "#d3edfa"
        )

        assertTrue(liveA.startsWith("123/"))
        assertNotEquals(liveA, liveB)
    }
}
