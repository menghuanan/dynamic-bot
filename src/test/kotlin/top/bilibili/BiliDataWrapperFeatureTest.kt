package top.bilibili

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.encodeToString
import top.bilibili.service.TemplateRuntimeCoordinator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun `wrapper serialization should omit legacy template maps`() {
        val uid = 123456L
        val yaml = Yaml(
            configuration = Yaml.default.configuration.copy(
                strictMode = false,
            ),
        )

        BiliData.dynamicPushTemplate["OneMsg"] = mutableSetOf("onebot11:group:10001")
        BiliData.dynamicPushTemplateByUid["onebot11:group:10001"] = mutableMapOf(uid to "TwoMsg")
        BiliData.dynamicTemplatePolicyByScope["contact:onebot11:group:10001"] = mutableMapOf(
            uid to TemplatePolicy(templates = mutableListOf("OneMsg")),
        )
        BiliData.atAll["onebot11:group:10001"] = mutableMapOf(uid to mutableSetOf(AtAllType.LIVE))

        val serialized = yaml.encodeToString(BiliDataWrapper.from(BiliData))

        assertFalse(serialized.contains("dynamicPushTemplate:"))
        assertFalse(serialized.contains("livePushTemplate:"))
        assertFalse(serialized.contains("liveCloseTemplate:"))
        assertFalse(serialized.contains("dynamicPushTemplateByUid:"))
        assertFalse(serialized.contains("livePushTemplateByUid:"))
        assertFalse(serialized.contains("liveCloseTemplateByUid:"))
        assertTrue(serialized.contains("dynamicTemplatePolicyByScope:"))
        assertTrue(serialized.contains("atAll:"))
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

    @Test
    fun `snapshot export should detach serialized policy state from live maps`() {
        val livePolicy = TemplatePolicy(templates = mutableListOf("OneMsg"), randomEnabled = true)
        BiliData.dynamicTemplatePolicyByScope["contact:onebot11:group:10001"] = mutableMapOf(123456L to livePolicy)

        val snapshot = TemplateRuntimeCoordinator.snapshotPolicies().dynamic
        livePolicy.templates += "TwoMsg"

        assertEquals(listOf("OneMsg"), snapshot["contact:onebot11:group:10001"]?.get(123456L)?.templates?.toList())
    }

    @Test
    fun `wrapper from should snapshot template policies before live maps change`() {
        val scope = "contact:onebot11:group:10001"
        val uid = 123456L
        BiliData.dynamicTemplatePolicyByScope[scope] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg"),
                randomEnabled = true,
            ),
        )

        val wrapper = BiliDataWrapper.from(BiliData)
        BiliData.dynamicTemplatePolicyByScope.getValue(scope).getValue(uid).templates += "TwoMsg"

        assertEquals(listOf("OneMsg"), wrapper.dynamicTemplatePolicyByScope[scope]?.get(uid)?.templates?.toList())
    }
}
