package top.bilibili.service

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliData
import top.bilibili.BiliConfigManager
import top.bilibili.BiliDataWrapper
import top.bilibili.Group
import top.bilibili.TemplatePolicy
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TemplateRuntimeCoordinatorTest {
    @AfterTest
    fun cleanup() {
        TemplateRuntimeCoordinator.replaceAllPolicies(
            dynamicPolicies = mutableMapOf(),
            livePolicies = mutableMapOf(),
            liveClosePolicies = mutableMapOf(),
        )
        BiliData.group.clear()
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
     * 删除分组时应同步清理 groupRef 运行态缓存，避免悬空分组继续影响模板选择。
     * 这里直接走 GroupService 入口，确保生命周期钩子而不是单独的协调器 API 被覆盖到。
     */
    @Test
    fun `deleting a group should clear group scope runtime state`() = runBlocking {
        bindManagerData()
        val scope = "groupRef:ops"
        val uid = 123456L
        BiliData.group["ops"] = Group(
            name = "ops",
            creator = 1L,
            contacts = mutableSetOf("onebot11:group:10001"),
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
            directScope = null,
            groupScopes = listOf(scope),
            messageIdentity = "dynamic:group-delete",
        )

        GroupService.delGroup("ops", operator = 1L)

        assertFalse(BiliData.dynamicTemplatePolicyByScope.containsKey(scope))
        assertTrue(runtimeState("lastTemplateByScopeKey").isEmpty())
        assertTrue(runtimeState("batchTemplateByMessageKey").isEmpty())
    }

    /**
     * 用新 wrapper 回填数据时应同步清空运行态缓存。
     * 否则 load/apply 后的 last-used 与批次缓存仍会引用旧策略作用域。
     */
    @Test
    fun `wrapper apply should clear runtime caches`() {
        val scope = "contact:onebot11:group:10001"
        val uid = 123456L
        BiliData.dynamicTemplatePolicyByScope[scope] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg"),
                randomEnabled = false,
            ),
        )
        TemplateSelectionService.selectTemplate(
            type = "dynamic",
            uid = uid,
            directScope = scope,
            groupScopes = emptyList(),
            messageIdentity = "dynamic:wrapper-apply",
        )

        BiliDataWrapper.applyTo(BiliDataWrapper(dataVersion = 4), BiliData)

        assertTrue(runtimeState("lastTemplateByScopeKey").isEmpty())
        assertTrue(runtimeState("batchTemplateByMessageKey").isEmpty())
    }

    /**
     * 读取协调层导出的运行态缓存快照，验证生命周期操作后是否已经清空。
     * 测试使用副本断言，不直接持有可变运行态引用。
     */
    private fun runtimeState(fieldName: String): Map<String, String> {
        return when (fieldName) {
            "lastTemplateByScopeKey" -> TemplateRuntimeCoordinator.snapshotLastTemplateState()
            "batchTemplateByMessageKey" -> TemplateRuntimeCoordinator.snapshotBatchTemplateState()
            else -> error("unknown runtime state: $fieldName")
        }
    }

    /**
     * 让 GroupService 在测试环境里显式指向当前 BiliData 单例。
     * 旧服务仍通过 BiliConfigManager.data 访问全局数据，因此测试前要补齐这个依赖。
     */
    private fun bindManagerData() {
        val field = BiliConfigManager::class.java.getDeclaredField("data")
        field.isAccessible = true
        field.set(BiliConfigManager, BiliData)
    }
}
