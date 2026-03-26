package top.bilibili.connector.onebot11

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformType
import top.bilibili.connector.onebot11.core.OneBot11MessageEvent
import top.bilibili.connector.onebot11.core.OneBot11MessageSegment

class OneBot11AdapterTest {
    @Test
    fun `message event should normalize into platform inbound message`() {
        val normalized = OneBot11Adapter.normalize(
            OneBot11MessageEvent(
                messageType = "group",
                messageId = 1,
                userId = 200L,
                message = listOf(
                    OneBot11MessageSegment("at", mapOf("qq" to "300")),
                    OneBot11MessageSegment("text", mapOf("text" to "/bili list")),
                    OneBot11MessageSegment(
                        type = "json",
                        data = mapOf(
                            "data" to """{"jumpUrl":"https:\/\/www.bilibili.com\/video\/BV1xx411c7mD"}""",
                        ),
                    ),
                ),
                rawMessage = "/bili list",
                groupId = 100L,
                selfId = 300L,
            )
        )

        assertEquals(PlatformType.ONEBOT11, normalized.platform)
        assertEquals(PlatformChatType.GROUP, normalized.chatType)
        assertEquals("100", normalized.chatId)
        assertEquals("200", normalized.senderId)
        assertEquals("300", normalized.selfId)
        assertEquals("/bili list", normalized.messageText)
        assertTrue(normalized.hasMention)
        assertFalse(normalized.fromSelf)
        assertEquals(
            listOf("/bili list", "https://www.bilibili.com/video/BV1xx411c7mD"),
            normalized.searchTexts,
        )
    }

    // 约束通用 OneBot11 适配核心必须只依赖新的传输契约，避免继续把 NapCatClient 绑死在协议层。
    @Test
    fun `generic onebot11 adapter should depend on transport contract instead of napcat client`() {
        val adapterSource = File("src/main/kotlin/top/bilibili/connector/onebot11/OneBot11Adapter.kt").readText()
        val genericAdapterFile = File("src/main/kotlin/top/bilibili/connector/onebot11/generic/GenericOneBot11Adapter.kt")

        assertTrue(genericAdapterFile.exists(), "generic OneBot11 adapter should exist")
        assertTrue(adapterSource.contains("OneBot11Transport"))
        assertFalse(adapterSource.contains("NapCatClient"))
        assertTrue(genericAdapterFile.readText().contains("OneBot11Transport"))
        assertFalse(genericAdapterFile.readText().contains("NapCatClient"))
    }
}
