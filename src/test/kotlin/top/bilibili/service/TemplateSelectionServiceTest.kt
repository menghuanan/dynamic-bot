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
    fun `selection should still resolve group scope policies after cache reset`() {
        TemplateRuntimeCoordinator.replaceAllPolicies(
            dynamicPolicies = mutableMapOf(
                "groupRef:ops" to mutableMapOf(
                    123456L to TemplatePolicy(
                        templates = mutableListOf("TwoMsg"),
                        randomEnabled = false,
                    ),
                ),
            ),
            livePolicies = mutableMapOf(),
            liveClosePolicies = mutableMapOf(),
        )

        val result = TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = 123456L,
            directScope = "contact:onebot11:group:10001",
            groupScopes = listOf("groupRef:ops"),
            messageIdentity = "dynamic:group-after-reset",
        )

        assertEquals("TwoMsg", result.templateName)
        assertEquals("groupRef:ops", result.scope)
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
        val segments = key.split("|")
        require(segments.size == 3) { "unexpected runtime key: $key" }
        TemplateRuntimeCoordinator.selectTemplateName(
            type = segments[0],
            uid = segments[2].toLong(),
            directScope = segments[1],
            groupScopes = emptyList(),
            messageIdentity = "prime:$templateName",
            templateExists = { candidate -> candidate == templateName },
        )
    }

    /**
     * 读取协调层导出的运行态快照。
     * 测试通过只读快照断言缓存结果，避免继续依赖模板选择服务的私有字段布局。
     */
    private fun runtimeState(fieldName: String): Map<String, String> {
        return when (fieldName) {
            "lastTemplateByScopeKey" -> TemplateRuntimeCoordinator.snapshotLastTemplateState()
            "batchTemplateByMessageKey" -> TemplateRuntimeCoordinator.snapshotBatchTemplateState()
            else -> error("unknown runtime state: $fieldName")
        }
    }
}
