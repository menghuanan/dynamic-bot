package top.bilibili.service

import top.bilibili.BiliData
import top.bilibili.TemplatePolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TemplateRuntimeCoordinatorTest {
    @AfterTest
    fun cleanup() {
        TemplateRuntimeCoordinator.replaceAllPolicies(
            dynamicPolicies = mutableMapOf(),
            livePolicies = mutableMapOf(),
            liveClosePolicies = mutableMapOf(),
        )
    }

    @Test
    fun `replacing all template policies should clear runtime caches and detach stored policy tables`() {
        val scope = "contact:onebot11:group:10001"
        val uid = 123456L
        val dynamicPolicies: MutableMap<String, MutableMap<Long, TemplatePolicy>> = mutableMapOf(
            scope to mutableMapOf(
                uid to TemplatePolicy(
                    templates = mutableListOf("OneMsg"),
                    randomEnabled = true,
                ),
            ),
        )

        BiliData.dynamicTemplatePolicyByScope[scope] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg", "TwoMsg"),
                randomEnabled = true,
            ),
        )
        TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = uid,
            directScope = scope,
            groupScopes = emptyList(),
            messageIdentity = "dynamic:replace-all",
        )

        TemplateRuntimeCoordinator.replaceAllPolicies(
            dynamicPolicies = dynamicPolicies,
            livePolicies = mutableMapOf(),
            liveClosePolicies = mutableMapOf(),
        )
        dynamicPolicies.getValue(scope).getValue(uid).templates += "TwoMsg"

        assertTrue(runtimeState("lastTemplateByScopeKey").isEmpty())
        assertTrue(runtimeState("batchTemplateByMessageKey").isEmpty())
        assertEquals(listOf("OneMsg"), BiliData.dynamicTemplatePolicyByScope[scope]?.get(uid)?.templates?.toList())
    }

    /**
     * 读取模板选择服务的运行态缓存，验证全量替换后是否已经清空。
     * 当前测试把反射限制在只读断言辅助里，避免为测试增加额外生产 API。
     */
    private fun runtimeState(fieldName: String): MutableMap<*, *> {
        val field = TemplateSelectionService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(TemplateSelectionService) as MutableMap<*, *>
    }
}
