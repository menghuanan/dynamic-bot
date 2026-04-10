package top.bilibili.service

import top.bilibili.BiliData
import top.bilibili.TemplatePolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class TemplateSelectionServiceTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamicTemplatePolicyByScope.clear()
        BiliData.liveTemplatePolicyByScope.clear()
        BiliData.liveCloseTemplatePolicyByScope.clear()
        clearRuntimeState("lastTemplateByScopeKey")
        clearRuntimeState("batchTemplateByMessageKey")
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

    private fun clearRuntimeState(fieldName: String) {
        val field = TemplateSelectionService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        (field.get(TemplateSelectionService) as MutableMap<*, *>).clear()
    }
}
