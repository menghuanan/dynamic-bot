package top.bilibili.connector.onebot11

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformType
import top.bilibili.napcat.MessageEvent
import top.bilibili.napcat.MessageSegment

class OneBot11AdapterTest {
    @Test
    fun `message event should normalize into platform inbound message`() {
        val normalized = OneBot11Adapter.normalize(
            MessageEvent(
                messageType = "group",
                messageId = 1,
                userId = 200L,
                message = listOf(
                    MessageSegment.at(300L),
                    MessageSegment.text("/bili list"),
                    MessageSegment(
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
}
