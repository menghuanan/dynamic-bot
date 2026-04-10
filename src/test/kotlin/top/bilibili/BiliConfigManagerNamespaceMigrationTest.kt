package top.bilibili

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BiliConfigManagerNamespaceMigrationTest {
    @AfterTest
    fun cleanup() {
        BiliData.dataVersion = 0
        BiliData.dynamic.clear()
        BiliData.filter.clear()
        BiliData.dynamicPushTemplate.clear()
        BiliData.livePushTemplate.clear()
        BiliData.liveCloseTemplate.clear()
        BiliData.dynamicPushTemplateByUid.clear()
        BiliData.livePushTemplateByUid.clear()
        BiliData.liveCloseTemplateByUid.clear()
        BiliData.dynamicTemplatePolicyByScope.clear()
        BiliData.liveTemplatePolicyByScope.clear()
        BiliData.liveCloseTemplatePolicyByScope.clear()
        BiliData.dynamicColorByUid.clear()
        BiliData.atAll.clear()
        BiliData.group.clear()
        BiliData.bangumi.clear()
        BiliData.linkParseBlacklist.clear()
    }

    @Test
    fun `migrateDataIfNeeded 应将旧联系人目标重写为 onebot11 命名空间`() {
        val legacySubject = "group:10001"
        val migratedSubject = "onebot11:group:10001"
        val customSubject = "custom:manual"
        val uid = 123456L

        BiliData.dataVersion = 2
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(legacySubject, customSubject),
            sourceRefs = mutableSetOf("direct:$legacySubject", "groupRef:ops"),
        )
        BiliData.filter[legacySubject] = mutableMapOf(uid to DynamicFilter())
        BiliData.filter[customSubject] = mutableMapOf(uid to DynamicFilter())
        BiliData.dynamicPushTemplate["OneMsg"] = mutableSetOf(legacySubject, customSubject)
        BiliData.livePushTemplate["DrawOnly"] = mutableSetOf(legacySubject)
        BiliData.liveCloseTemplate["SimpleMsg"] = mutableSetOf(legacySubject)
        BiliData.dynamicPushTemplateByUid[legacySubject] = mutableMapOf(uid to "OneMsg")
        BiliData.livePushTemplateByUid[legacySubject] = mutableMapOf(uid to "DrawOnly")
        BiliData.liveCloseTemplateByUid[legacySubject] = mutableMapOf(uid to "SimpleMsg")
        BiliData.dynamicColorByUid[legacySubject] = mutableMapOf(uid to "#d3edfa")
        BiliData.atAll[legacySubject] = mutableMapOf(uid to mutableSetOf(AtAllType.LIVE))
        BiliData.group["ops"] = Group(name = "ops", creator = 1L, contacts = mutableSetOf(legacySubject, customSubject))
        BiliData.bangumi[404L] = Bangumi(
            title = "测试番剧",
            seasonId = 404L,
            mediaId = 505L,
            type = "番剧",
            contacts = mutableSetOf(legacySubject, customSubject),
        )

        val changed = migrateViaReflection()

        assertTrue(changed, "存在旧联系人目标时，迁移应报告已变更")
        assertTrue(BiliData.dataVersion >= 3, "命名空间迁移后数据版本应递增")
        assertEquals(setOf(migratedSubject, customSubject), BiliData.dynamic[uid]?.contacts?.toSet())
        assertEquals(setOf("direct:$migratedSubject", "groupRef:ops"), BiliData.dynamic[uid]?.sourceRefs?.toSet())
        assertTrue(BiliData.filter.containsKey(migratedSubject))
        assertFalse(BiliData.filter.containsKey(legacySubject))
        assertTrue(customSubject in BiliData.filter.keys, "自定义键应被保留")
        assertEquals(setOf(migratedSubject, customSubject), BiliData.dynamicPushTemplate["OneMsg"]?.toSet())
        assertEquals("OneMsg", BiliData.dynamicPushTemplateByUid[migratedSubject]?.get(uid))
        assertEquals("#d3edfa", BiliData.dynamicColorByUid[migratedSubject]?.get(uid))
        assertTrue(BiliData.atAll.containsKey(migratedSubject))
        assertEquals(setOf(migratedSubject, customSubject), BiliData.group["ops"]?.contacts?.toSet())
        assertEquals(setOf(migratedSubject, customSubject), BiliData.bangumi[404L]?.contacts?.toSet())
    }

    @Test
    fun `migrate should convert legacy template bindings into template policy by scope`() {
        val subject = "onebot11:group:10001"
        val uid = 123456L

        BiliData.dataVersion = 3
        BiliData.dynamicPushTemplate["OneMsg"] = mutableSetOf(subject)
        BiliData.dynamicPushTemplateByUid[subject] = mutableMapOf(uid to "TwoMsg")
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(subject),
            sourceRefs = mutableSetOf("direct:$subject"),
        )

        migrateViaReflection()

        val contactScope = "contact:$subject"
        assertEquals("TwoMsg", BiliData.dynamicTemplatePolicyByScope[contactScope]?.get(uid)?.templates?.firstOrNull())
        assertFalse(BiliData.dynamicTemplatePolicyByScope[contactScope]?.get(uid)?.randomEnabled == true)
    }

    private fun migrateViaReflection(): Boolean {
        val method = BiliConfigManager::class.java.getDeclaredMethod("migrateDataIfNeeded", BiliData::class.java)
        method.isAccessible = true
        return method.invoke(BiliConfigManager, BiliData) as Boolean
    }
}
