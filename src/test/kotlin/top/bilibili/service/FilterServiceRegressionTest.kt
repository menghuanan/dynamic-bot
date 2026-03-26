package top.bilibili.service

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliData
import top.bilibili.DynamicFilter
import top.bilibili.DynamicFilterType
import top.bilibili.SubData

class FilterServiceRegressionTest {
    private val uid = 123456L
    private val subject = "group:10001"

    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.filter.clear()
    }

    @Test
    fun `listFilter 在过滤列表为空时应返回明确提示`() = runBlocking {
        BiliData.dynamic[uid] = SubData(
            name = "测试用户",
            contacts = mutableSetOf(subject),
        )
        BiliData.filter[subject] = mutableMapOf(uid to DynamicFilter())

        val result = FilterService.listFilter(uid, subject)

        assertTrue(result.isNotBlank())
        assertFalse(BiliData.filter.containsKey(subject), "list 之后应清理空过滤器条目")
    }

    @Test
    fun `delFilter 应拒绝等于列表大小的索引`() = runBlocking {
        BiliData.dynamic[uid] = SubData(
            name = "测试用户",
            contacts = mutableSetOf(subject),
        )
        val dynamicFilter = DynamicFilter()
        dynamicFilter.typeSelect.list.add(DynamicFilterType.VIDEO)
        BiliData.filter[subject] = mutableMapOf(uid to dynamicFilter)

        val result = FilterService.delFilter("t1", uid, subject)

        assertTrue(result.isNotBlank())
        assertTrue(BiliData.filter.containsKey(subject))
    }

    @Test
    fun `delFilter 删除最后一条规则后应清理空过滤器条目`() = runBlocking {
        BiliData.dynamic[uid] = SubData(
            name = "测试用户",
            contacts = mutableSetOf(subject),
        )
        val dynamicFilter = DynamicFilter()
        dynamicFilter.typeSelect.list.add(DynamicFilterType.VIDEO)
        BiliData.filter[subject] = mutableMapOf(uid to dynamicFilter)

        val result = FilterService.delFilter("t0", uid, subject)

        assertTrue(result.isNotBlank())
        assertFalse(BiliData.filter.containsKey(subject), "应从 BiliData 中移除残留过滤器")
    }
}
