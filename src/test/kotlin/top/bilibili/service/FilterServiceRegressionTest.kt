package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FilterServiceRegressionTest {
    private val uid = 123456L
    private val subject = "group:10001"

    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.filter.clear()
    }

    @Test
    fun `listFilter should return explicit message when all filter lists are empty`() = runBlocking {
        BiliData.dynamic[uid] = SubData(
            name = "tester",
            contacts = mutableSetOf(subject)
        )
        BiliData.filter[subject] = mutableMapOf(uid to DynamicFilter())

        val result = FilterService.listFilter(uid, subject)

        assertEquals("当前目标没有过滤器", result)
        assertFalse(BiliData.filter.containsKey(subject), "empty filter entry should be cleaned up after list")
    }

    @Test
    fun `delFilter should reject index equal to list size`() = runBlocking {
        BiliData.dynamic[uid] = SubData(
            name = "tester",
            contacts = mutableSetOf(subject)
        )
        val dynamicFilter = DynamicFilter()
        dynamicFilter.typeSelect.list.add(top.bilibili.DynamicFilterType.VIDEO)
        BiliData.filter[subject] = mutableMapOf(uid to dynamicFilter)

        val result = FilterService.delFilter("t1", uid, subject)

        assertEquals("索引超出范围", result)
    }

    @Test
    fun `delFilter should cleanup empty filter entry after deleting last rule`() = runBlocking {
        BiliData.dynamic[uid] = SubData(
            name = "tester",
            contacts = mutableSetOf(subject)
        )
        val dynamicFilter = DynamicFilter()
        dynamicFilter.typeSelect.list.add(top.bilibili.DynamicFilterType.VIDEO)
        BiliData.filter[subject] = mutableMapOf(uid to dynamicFilter)

        val result = FilterService.delFilter("t0", uid, subject)

        assertEquals("已删除 视频 类型过滤", result)
        assertFalse(BiliData.filter.containsKey(subject), "filter residue should be removed from BiliData")
    }
}
