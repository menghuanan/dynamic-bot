package top.bilibili.tasker

import kotlinx.coroutines.runBlocking
import top.bilibili.BiliData
import top.bilibili.Group
import top.bilibili.SubData
import top.bilibili.TemplatePolicy
import top.bilibili.connector.OutgoingPart
import top.bilibili.data.DynamicMessage
import top.bilibili.data.DynamicType
import top.bilibili.service.TemplateSelectionService
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SendTaskerTemplateSelectionTest {
    @AfterTest
    fun cleanup() {
        BiliData.dynamic.clear()
        BiliData.group.clear()
        BiliData.dynamicTemplatePolicyByScope.clear()
        clearRuntimeState("lastTemplateByScopeKey")
        clearRuntimeState("batchTemplateByMessageKey")
    }

    @Test
    fun `same group contacts should reuse one random template within one send batch`() = runBlocking {
        val uid = 123456L
        val firstContact = "onebot11:group:10001"
        val secondContact = "onebot11:group:10002"
        BiliData.group["ops"] = Group(
            name = "ops",
            creator = 1L,
            contacts = mutableSetOf(firstContact, secondContact),
        )
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(firstContact, secondContact),
            sourceRefs = mutableSetOf("groupRef:ops"),
        )
        BiliData.dynamicTemplatePolicyByScope["groupRef:ops"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg", "TwoMsg"),
                randomEnabled = true,
            ),
        )

        val message = buildDynamicMessage(uid = uid, did = "9001")
        val firstSegments = SendTasker.warmupMessageBuildPath(message, firstContact)
        val secondSegments = SendTasker.warmupMessageBuildPath(message, secondContact)

        assertEquals(extractText(firstSegments), extractText(secondSegments))
        assertTrue(extractText(firstSegments).contains("测试UP"))
    }

    @Test
    fun `direct contact policies should stay isolated across contacts`() = runBlocking {
        val uid = 123456L
        val firstContact = "onebot11:group:10001"
        val secondContact = "onebot11:group:10002"
        BiliData.dynamic[uid] = SubData(
            name = "测试UP",
            contacts = mutableSetOf(firstContact, secondContact),
            sourceRefs = mutableSetOf("direct:$firstContact", "direct:$secondContact"),
        )
        BiliData.dynamicTemplatePolicyByScope["contact:$firstContact"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("OneMsg"),
                randomEnabled = false,
            ),
        )
        BiliData.dynamicTemplatePolicyByScope["contact:$secondContact"] = mutableMapOf(
            uid to TemplatePolicy(
                templates = mutableListOf("TwoMsg"),
                randomEnabled = false,
            ),
        )

        val message = buildDynamicMessage(uid = uid, did = "9002")
        val firstSegments = SendTasker.warmupMessageBuildPath(message, firstContact)
        val secondSegments = SendTasker.warmupMessageBuildPath(message, secondContact)
        val firstText = extractText(firstSegments)
        val secondText = extractText(secondSegments)

        assertNotEquals(firstText, secondText)
        assertTrue(firstText.contains("测试UP@"))
        assertTrue(!firstText.contains("@123456@"))
        assertTrue(secondText.contains("@123456@"))
    }

    private fun buildDynamicMessage(uid: Long, did: String): DynamicMessage {
        return DynamicMessage(
            did = did,
            mid = uid,
            name = "测试UP",
            type = DynamicType.DYNAMIC_TYPE_AV,
            time = "2026-03-20 12:00:00",
            timestamp = 1773998400,
            content = "测试正文",
            images = emptyList(),
            links = emptyList(),
            drawPath = null,
            contact = null,
        )
    }

    private fun extractText(segments: List<OutgoingPart>): String {
        return segments
            .filterIsInstance<OutgoingPart.Text>()
            .joinToString("\n") { it.text }
    }

    private fun clearRuntimeState(fieldName: String) {
        val field = TemplateSelectionService::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        (field.get(TemplateSelectionService) as MutableMap<*, *>).clear()
    }
}
