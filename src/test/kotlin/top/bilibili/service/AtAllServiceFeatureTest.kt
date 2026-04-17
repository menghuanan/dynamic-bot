package top.bilibili.service

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.AtAllType
import top.bilibili.BiliData
import top.bilibili.SubData
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.data.LiveCloseMessage
import top.bilibili.data.LiveMessage

class AtAllServiceFeatureTest {
    private val subject = "group:10001"
    private val persistedSubject = "onebot11:group:10001"

    @AfterTest
    fun cleanup() {
        BiliData.atAll.clear()
        BiliData.dynamic.clear()
    }

    @Test
    fun `shouldAtAll 应兼容旧运行时参数并读取带命名空间的持久化目标`() = runBlocking {
        BiliData.atAll[persistedSubject] = mutableMapOf(
            123L to mutableSetOf(AtAllType.VIDEO),
        )

        val dynamic = dynamicMessage(type = DynamicType.DYNAMIC_TYPE_AV, uid = 123L)
        val live = liveMessage(uid = 456L)

        assertTrue(AtAllService.shouldAtAll(subject, 123L, dynamic))
        assertFalse(AtAllService.shouldAtAll(subject, 456L, live))
    }

    @Test
    fun `shouldAtAll 不应对下播消息触发全体`() = runBlocking {
        BiliData.atAll[persistedSubject] = mutableMapOf(
            0L to mutableSetOf(AtAllType.LIVE),
        )

        val close = liveCloseMessage(uid = 789L)
        assertFalse(AtAllService.shouldAtAll(subject, 789L, close))
    }

    @Test
    fun `addAtAll 应拒绝零 UID 以避免浪费群级配额`() = runBlocking {
        val result = AtAllService.addAtAll("live", 0L, subject)
        assertTrue(result.isNotBlank())
        assertTrue(BiliData.atAll.isEmpty(), "UID 无效时不应写入配置")
    }

    @Test
    fun `addAtAll 应在目标 UID 未订阅到当前目标时失败`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP", contacts = mutableSetOf("onebot11:group:999"))

        val result = AtAllService.addAtAll("live", 123L, subject)
        assertTrue(result.isNotBlank())
        assertTrue(BiliData.atAll.isEmpty(), "未订阅 UID 不应写入配置")
    }

    @Test
    fun `delAtAll 应清理空嵌套映射以保持持久化一致性`() = runBlocking {
        BiliData.dynamic[123L] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))
        AtAllService.addAtAll("live", 123L, subject)

        AtAllService.delAtAll("live", 123L, subject)

        assertFalse(BiliData.atAll.containsKey(persistedSubject), "删除后应移除空的目标映射")
    }

    // 当前设计会把“全部动态”和细分类型做规范化折叠，避免在持久化里保留语义重复的组合。
    @Test
    fun `addAtAll 应在 d l v 顺序下收敛为直播和视频`() = runBlocking {
        val uid = 3108865L
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))

        AtAllService.addAtAll("d", uid, subject)
        AtAllService.addAtAll("l", uid, subject)
        AtAllService.addAtAll("v", uid, subject)

        val stored = BiliData.atAll[persistedSubject]?.get(uid)
        assertEquals(linkedSetOf(AtAllType.LIVE, AtAllType.VIDEO), stored, "细分视频类型后应移除全部动态，只保留直播和视频")
        assertEquals("UID($uid): 直播,视频", AtAllService.listAtAll(subject = subject), "列表展示应反映规范化后的存储结果")
    }

    // 当前设计会在重新写入“全部动态”时回收已存在的视频/音乐/专栏细分项，保持宽口径优先。
    @Test
    fun `addAtAll 应在重新写入 d 时覆盖已存在的视频类型`() = runBlocking {
        val uid = 3108865L
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))

        AtAllService.addAtAll("v", uid, subject)
        AtAllService.addAtAll("d", uid, subject)

        val stored = BiliData.atAll[persistedSubject]?.get(uid)
        assertEquals(linkedSetOf(AtAllType.DYNAMIC), stored, "重新写入全部动态后应移除视频细分项")
        assertEquals("UID($uid): 全部动态", AtAllService.listAtAll(subject = subject), "列表展示应回到全部动态")
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
