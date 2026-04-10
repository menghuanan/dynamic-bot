package top.bilibili.service

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.BiliData
import top.bilibili.Group
import top.bilibili.SubData
import top.bilibili.TemplatePolicy

class TemplateServiceFeatureTest {
    private val subject = "group:10001"
    private val persistedSubject = "onebot11:group:10001"
    private val uid = 123456L

    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.dynamicPushTemplate.clear()
        BiliData.livePushTemplate.clear()
        BiliData.liveCloseTemplate.clear()
        BiliData.dynamicPushTemplateByUid.clear()
        BiliData.livePushTemplateByUid.clear()
        BiliData.liveCloseTemplateByUid.clear()
        BiliData.dynamicTemplatePolicyByScope.clear()
        BiliData.liveTemplatePolicyByScope.clear()
        BiliData.liveCloseTemplatePolicyByScope.clear()
        BiliData.group.clear()
        TemplateRuntimeCoordinator.replaceAllPolicies(
            dynamicPolicies = mutableMapOf(),
            livePolicies = mutableMapOf(),
            liveClosePolicies = mutableMapOf(),
        )
    }

    @Test
    fun `模板说明应仅保留面向用户的指引`() {
        val text = TemplateService.explainTemplate("d")
        assertFalse(text.contains("preview"))
        assertFalse(text.contains("gateway"))
    }

    @Test
    fun `setTemplate 应支持 UID 作用域绑定并持久化命名空间目标`() {
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))

        val result = TemplateService.setTemplate("d", "OneMsg", subject, uid)
        val policy = BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"]?.get(uid)

        assertTrue(result.isNotBlank())
        assertEquals(listOf("OneMsg"), policy?.templates?.toList())
    }

    @Test
    fun `setTemplate 应拒绝未订阅的 UID 绑定`() {
        val result = TemplateService.setTemplate("d", "OneMsg", subject, uid)
        assertTrue(result.isNotBlank())
        assertTrue(BiliData.dynamicTemplatePolicyByScope.isEmpty())
    }

    @Test
    fun `add should append template into policy for uid scope`() {
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))

        val result = TemplateService.addTemplate("d", "OneMsg", subject, uid, null)
        val policy = BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"]?.get(uid)

        assertTrue(result.contains("成功"))
        assertEquals(listOf("OneMsg"), policy?.templates?.toList())
    }

    @Test
    fun `del should disable random when only one template remains`() {
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))
        BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg", "TwoMsg"),
                randomEnabled = true,
            ),
        )

        val result = TemplateService.deleteTemplate("d", "TwoMsg", subject, uid, null)
        val policy = BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"]?.get(uid)

        assertTrue(result.contains("成功"))
        assertEquals(listOf("OneMsg"), policy?.templates?.toList())
        assertFalse(policy?.randomEnabled == true)
    }

    @Test
    fun `deleting the last template from a scope should clear runtime state for that scope`() {
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))
        BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg"),
                randomEnabled = false,
            ),
        )
        TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = uid,
            directScope = "contact:$persistedSubject",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:remove-last",
        )

        val result = TemplateService.deleteTemplate("d", "OneMsg", subject, uid, null)

        assertTrue(result.contains("成功"))
        assertFalse(BiliData.dynamicTemplatePolicyByScope.containsKey("contact:$persistedSubject"))
        assertFalse(runtimeState("lastTemplateByScopeKey").containsKey("dynamic|contact:$persistedSubject|$uid"))
    }

    @Test
    fun `deleting one uid last template should clear only that uid runtime state while preserving the scope`() {
        val secondUid = 654321L
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))
        BiliData.dynamic[secondUid] = SubData(name = "测试UP2", contacts = mutableSetOf(persistedSubject))
        BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg"),
                randomEnabled = false,
            ),
            secondUid to TemplatePolicy(
                templates = mutableListOf("TwoMsg"),
                randomEnabled = false,
            ),
        )
        TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = uid,
            directScope = "contact:$persistedSubject",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:first-uid",
        )
        TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = secondUid,
            directScope = "contact:$persistedSubject",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:second-uid",
        )

        val result = TemplateService.deleteTemplate("d", "OneMsg", subject, uid, null)

        assertTrue(result.contains("成功"))
        assertFalse(BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"]?.containsKey(uid) == true)
        assertTrue(BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"]?.containsKey(secondUid) == true)
        assertFalse(runtimeState("lastTemplateByScopeKey").containsKey("dynamic|contact:$persistedSubject|$uid"))
        assertTrue(runtimeState("lastTemplateByScopeKey").containsKey("dynamic|contact:$persistedSubject|$secondUid"))
    }

    @Test
    fun `on and off should toggle random mode after validation`() {
        BiliData.dynamic[uid] = SubData(name = "测试UP", contacts = mutableSetOf(persistedSubject))
        BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg", "TwoMsg"),
                randomEnabled = false,
            ),
        )

        val onResult = TemplateService.enableRandom("d", subject, uid, null)
        val offResult = TemplateService.disableRandom("d", subject, uid, null)

        assertTrue(onResult.contains("成功"))
        assertTrue(offResult.contains("成功"))
        assertFalse(BiliData.dynamicTemplatePolicyByScope["contact:$persistedSubject"]?.get(uid)?.randomEnabled == true)
    }

    @Test
    fun `group scope commands should write into groupRef policy maps`() {
        BiliData.group["ops"] = Group(
            name = "ops",
            creator = 1L,
            contacts = mutableSetOf(persistedSubject),
        )
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(persistedSubject),
            sourceRefs = mutableSetOf("groupRef:ops"),
        )

        val result = TemplateService.addTemplate("d", "OneMsg", subject, uid, "ops")
        val policy = BiliData.dynamicTemplatePolicyByScope["groupRef:ops"]?.get(uid)

        assertTrue(result.contains("成功"))
        assertEquals(listOf("OneMsg"), policy?.templates?.toList())
    }

    /**
     * 读取协调层导出的运行态快照，用于断言删除策略后的清理效果。
     * 测试侧只观察副本，避免直接触碰运行中的真实缓存结构。
     */
    private fun runtimeState(fieldName: String): Map<String, String> {
        return when (fieldName) {
            "lastTemplateByScopeKey" -> TemplateRuntimeCoordinator.snapshotLastTemplateState()
            "batchTemplateByMessageKey" -> TemplateRuntimeCoordinator.snapshotBatchTemplateState()
            else -> error("unknown runtime state: $fieldName")
        }
    }
}
