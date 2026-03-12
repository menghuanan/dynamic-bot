package top.bilibili.tasker

import top.bilibili.BiliData
import top.bilibili.SubData
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicMessageTaskerNameSyncTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
    }

    @Test
    fun `syncSubscriptionName should update stored name when latest author name changes`() {
        val uid = 123456L
        BiliData.dynamic[uid] = SubData(name = "old-name")

        DynamicMessageTasker.syncSubscriptionName(uid, "new-name")

        assertEquals("new-name", BiliData.dynamic[uid]?.name)
    }
}
