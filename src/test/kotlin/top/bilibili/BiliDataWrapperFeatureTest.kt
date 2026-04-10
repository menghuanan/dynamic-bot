package top.bilibili

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BiliDataWrapperFeatureTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamicPushTemplateByUid.clear()
        BiliData.livePushTemplateByUid.clear()
        BiliData.liveCloseTemplateByUid.clear()
        BiliData.dynamicTemplatePolicyByScope.clear()
        BiliData.liveTemplatePolicyByScope.clear()
        BiliData.liveCloseTemplatePolicyByScope.clear()
        BiliData.dynamicColorByUid.clear()
        BiliData.atAll.clear()
    }

    @Test
    fun `wrapper roundtrip should preserve template and atall uid maps`() {
        val subject = "group:10001"
        val uid = 123456L

        BiliData.dynamicPushTemplateByUid[subject] = mutableMapOf(uid to "OneMsg")
        BiliData.livePushTemplateByUid[subject] = mutableMapOf(uid to "DrawOnly")
        BiliData.liveCloseTemplateByUid[subject] = mutableMapOf(uid to "SimpleMsg")
        BiliData.atAll[subject] = mutableMapOf(uid to mutableSetOf(AtAllType.LIVE))

        val wrapper = BiliDataWrapper.from(BiliData)
        BiliData.dynamicPushTemplateByUid = mutableMapOf()
        BiliData.livePushTemplateByUid = mutableMapOf()
        BiliData.liveCloseTemplateByUid = mutableMapOf()
        BiliData.atAll = mutableMapOf()

        BiliDataWrapper.applyTo(wrapper, BiliData)

        assertEquals("OneMsg", BiliData.dynamicPushTemplateByUid[subject]?.get(uid))
        assertEquals("DrawOnly", BiliData.livePushTemplateByUid[subject]?.get(uid))
        assertEquals("SimpleMsg", BiliData.liveCloseTemplateByUid[subject]?.get(uid))
        assertTrue(BiliData.atAll[subject]?.get(uid)?.contains(AtAllType.LIVE) == true)
    }

    @Test
    fun `wrapper roundtrip should preserve subject scoped color bindings`() {
        val uid = 123456L
        val firstSubject = "group:10001"
        val secondSubject = "group:10002"

        BiliData.dynamicColorByUid[firstSubject] = mutableMapOf(uid to "#d3edfa")
        BiliData.dynamicColorByUid[secondSubject] = mutableMapOf(uid to "#fde8ed")

        val wrapper = BiliDataWrapper.from(BiliData)
        BiliData.dynamicColorByUid = mutableMapOf()

        BiliDataWrapper.applyTo(wrapper, BiliData)

        assertEquals("#d3edfa", BiliData.dynamicColorByUid[firstSubject]?.get(uid))
        assertEquals("#fde8ed", BiliData.dynamicColorByUid[secondSubject]?.get(uid))
    }

    @Test
    fun `wrapper roundtrip should preserve template policy by scope maps`() {
        val scope = "contact:onebot11:group:10001"
        val uid = 123456L

        BiliData.dynamicTemplatePolicyByScope[scope] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg", "TwoMsg"),
                randomEnabled = true,
            ),
        )

        val wrapper = BiliDataWrapper.from(BiliData)
        BiliData.dynamicTemplatePolicyByScope = mutableMapOf<String, MutableMap<Long, TemplatePolicy>>()

        BiliDataWrapper.applyTo(wrapper, BiliData)
        val restoredPolicy = BiliData.dynamicTemplatePolicyByScope[scope]?.get(uid)

        assertEquals(listOf("OneMsg", "TwoMsg"), restoredPolicy?.templates?.toList())
        assertTrue(restoredPolicy?.randomEnabled == true)
    }
}
