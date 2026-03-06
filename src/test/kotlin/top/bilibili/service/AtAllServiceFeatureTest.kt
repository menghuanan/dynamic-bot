package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.AtAllType
import top.bilibili.BiliData
import top.bilibili.SubData
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class AtAllServiceFeatureTest {
    private val subject = "group:10001"

    @AfterTest
    fun cleanup() {
        BiliData.atAll.clear()
        BiliData.dynamic.clear()
    }

    @Test
    fun `shouldAtAll should only use uid scope`() = runBlocking {
        BiliData.atAll[subject] = mutableMapOf(
            123L to mutableSetOf(AtAllType.VIDEO),
        )

        val dynamic = dynamicMessage(type = DynamicType.DYNAMIC_TYPE_AV, uid = 123L)
        val live = liveMessage(uid = 456L)

        assertTrue(AtAllService.shouldAtAll(subject, 123L, dynamic))
        assertFalse(AtAllService.shouldAtAll(subject, 456L, live))
    }

    @Test
    fun `shouldAtAll should not at-all for live close message`() = runBlocking {
        BiliData.atAll[subject] = mutableMapOf(
            0L to mutableSetOf(AtAllType.LIVE),
        )

        val close = liveCloseMessage(uid = 789L)
        assertFalse(AtAllService.shouldAtAll(subject, 789L, close))
    }

    @Test
    fun `addAtAll should reject uid zero to avoid group-wide quota waste`() = runBlocking {
        val result = AtAllService.addAtAll("live", 0L, subject)
        assertTrue(result.contains("UID"), "should require explicit uid")
        assertTrue(BiliData.atAll.isEmpty(), "should not write config when uid is invalid")
    }

    @Test
    fun `addAtAll should fail when target uid is not subscribed in subject`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP", contacts = mutableSetOf("group:999"))

        val result = AtAllService.addAtAll("live", 123L, subject)
        assertTrue(result.contains("未订阅"), "should guard against unbound uid")
        assertTrue(BiliData.atAll.isEmpty(), "should not write config for unsubscribed uid")
    }

    @Test
    fun `delAtAll should cleanup empty nested map for persistence consistency`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP", contacts = mutableSetOf(subject))
        AtAllService.addAtAll("live", 123L, subject)

        val result = AtAllService.delAtAll("live", 123L, subject)
        assertEquals("删除成功", result)
        assertFalse(BiliData.atAll.containsKey(subject), "empty subject map should be removed after deletion")
    }

    private fun dynamicMessage(type: DynamicType, uid: Long): DynamicMessage {
        return DynamicMessage(
            did = "100",
            mid = uid,
            name = "测试UP",
            type = type,
            time = "2026-03-06 12:00:00",
            timestamp = 1772788800,
            content = "测试内容",
            images = emptyList(),
            links = emptyList(),
            drawPath = null,
            contact = null,
        )
    }

    private fun liveMessage(uid: Long): LiveMessage {
        return LiveMessage(
            rid = 1000L,
            mid = uid,
            name = "测试主播",
            time = "2026-03-06 12:00:00",
            timestamp = 1772788800,
            title = "测试直播",
            cover = "https://example.com/cover.jpg",
            area = "测试分区",
            link = "https://live.bilibili.com/1000",
            drawPath = null,
            contact = null,
        )
    }

    private fun liveCloseMessage(uid: Long): LiveCloseMessage {
        return LiveCloseMessage(
            rid = 1000L,
            mid = uid,
            name = "测试主播",
            time = "2026-03-06 12:00:00",
            timestamp = 1772788800,
            endTime = "2026-03-06 13:00:00",
            duration = "1小时",
            title = "测试直播",
            area = "测试分区",
            link = "https://live.bilibili.com/1000",
            drawPath = null,
            contact = null,
        )
    }
}
