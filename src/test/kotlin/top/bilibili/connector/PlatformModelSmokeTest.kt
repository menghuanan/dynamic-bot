package top.bilibili.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class PlatformModelSmokeTest {
    @Test
    fun `platform contact should preserve namespace and contact kind`() {
        val contact = PlatformContact(
            platform = PlatformType.ONEBOT11,
            type = PlatformChatType.GROUP,
            id = "123456",
        )

        assertEquals(PlatformType.ONEBOT11, contact.platform)
        assertEquals(PlatformChatType.GROUP, contact.type)
        assertEquals("123456", contact.id)
    }

    @Test
    fun `outgoing image part should preserve neutral image source`() {
        val part = OutgoingPart.image(ImageSource.LocalFile("temp/test.png"))

        val image = assertIs<OutgoingPart.Image>(part)
        assertEquals("temp/test.png", assertIs<ImageSource.LocalFile>(image.source).path)
    }
}
