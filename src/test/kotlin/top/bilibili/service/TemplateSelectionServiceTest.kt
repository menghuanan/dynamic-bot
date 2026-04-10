package top.bilibili.service

import top.bilibili.BiliData
import top.bilibili.TemplatePolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TemplateSelectionServiceTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamicTemplatePolicyByScope.clear()
        BiliData.liveTemplatePolicyByScope.clear()
        BiliData.liveCloseTemplatePolicyByScope.clear()
        TemplateRuntimeCoordinator.replaceAllPolicies(
            dynamicPolicies = mutableMapOf(),
            livePolicies = mutableMapOf(),
            liveClosePolicies = mutableMapOf(),
        )
    }

    @Test
    fun `fixed selection should use the first template in policy order`() {
        BiliData.dynamicTemplatePolicyByScope["contact:onebot11:group:10001"] = mutableMapOf(
            123456L to TemplatePolicy(
                templates = mutableListOf("TwoMsg", "OneMsg"),
                randomEnabled = false,
            ),
        )

        val result = TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = 123456L,
            directScope = "contact:onebot11:group:10001",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:100",
        )

        assertEquals("TwoMsg", result.templateName)
    }

    @Test
    fun `random selection should avoid using the same template consecutively`() {
        BiliData.dynamicTemplatePolicyByScope["contact:onebot11:group:10001"] = mutableMapOf(
            123456L to TemplatePolicy(
                templates = mutableListOf("OneMsg", "TwoMsg"),
                randomEnabled = true,
            ),
        )

        val result1 = TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = 123456L,
            directScope = "contact:onebot11:group:10001",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:101",
        )
        val result2 = TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = 123456L,
            directScope = "contact:onebot11:group:10001",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:102",
        )

        assertNotEquals(result1.templateName, result2.templateName)
    }

    @Test
    fun `random selection should degrade to the only valid template when invalid entries are filtered`() {
        BiliData.dynamicTemplatePolicyByScope["contact:onebot11:group:10001"] = mutableMapOf(
            123456L to TemplatePolicy(
                templates = mutableListOf("MissingTemplate", "TwoMsg"),
                randomEnabled = true,
            ),
        )

        val result = TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = 123456L,
            directScope = "contact:onebot11:group:10001",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:103",
        )

        assertEquals("TwoMsg", result.templateName)
    }

    @Test
    fun `selection should fall back to global default when policy has no valid templates`() {
        BiliData.dynamicTemplatePolicyByScope["contact:onebot11:group:10001"] = mutableMapOf(
            123456L to TemplatePolicy(
                templates = mutableListOf("MissingTemplate"),
                randomEnabled = false,
            ),
        )

        val result = TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = 123456L,
            directScope = "contact:onebot11:group:10001",
            groupScopes = emptyList(),
            messageIdentity = "dynamic:104",
        )

        assertEquals("OneMsg", result.templateName)
    }

    @Test
    fun `removing a scope should clear last-used runtime state for that scope`() {
        primeLastTemplate("dynamic|contact:onebot11:group:10001|123456", "OneMsg")

        TemplateRuntimeCoordinator.removeScope("dynamic", "contact:onebot11:group:10001")

        assertFalse(runtimeState("lastTemplateByScopeKey").containsKey("dynamic|contact:onebot11:group:10001|123456"))
    }

    @Test
    fun `removing uid policies should clear last-used runtime state for that uid across types`() {
        primeLastTemplate("dynamic|contact:onebot11:group:10001|123456", "OneMsg")
        primeLastTemplate("live|groupRef:ops|123456", "DrawOnly")

        TemplateRuntimeCoordinator.removeUidAcrossTypes(123456L)

        assertTrue(runtimeState("lastTemplateByScopeKey").isEmpty())
    }

    /**
     * 为运行态测试预置 last-used 模板记录。
     * 这里仍通过反射写入私有缓存，直到协调器暴露完整的运行态观测入口。
     */
    private fun primeLastTemplate(key: String, templateName: String) {
        @Suppress("UNCHECKED_CAST")
        val state = runtimeState("lastTemplateByScopeKey") as MutableMap<String, String>
        state[key] = templateName
    }

    /**
     * 读取模板选择服务的私有运行态缓存。
     * 当前测试只把反射限制在断言辅助里，清理动作统一走协调器公开 API。
     */
    private fun runtimeState(fieldName: String): MutableMap<*, *> {
        val field = TemplateSelectionService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(TemplateSelectionService) as MutableMap<*, *>
    }
}
