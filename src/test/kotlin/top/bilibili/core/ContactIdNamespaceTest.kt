package top.bilibili.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import top.bilibili.connector.PlatformChatType
import top.bilibili.connector.PlatformContact
import top.bilibili.connector.PlatformType
import top.bilibili.utils.parseCommandPlatformContact
import top.bilibili.utils.parsePlatformContact

class ContactIdNamespaceTest {
    @Test
    fun `toString 应输出带命名空间的 onebot11 联系人 ID`() {
        assertEquals("onebot11:group:123456", ContactId.group(123456L).toString())
        assertEquals("onebot11:private:654321", ContactId.private(654321L).toString())
    }

    @Test
    fun `from 应同时接受带命名空间和旧格式的 onebot11 联系人 ID`() {
        assertEquals(ContactId.group(10001L), ContactId.from("onebot11:group:10001"))
        assertEquals(ContactId.private(20002L), ContactId.from("onebot11:private:20002"))
        assertEquals(ContactId.group(10001L), ContactId.from("group:10001"))
        assertEquals(ContactId.private(20002L), ContactId.from("private:20002"))
        assertEquals(ContactId.group(10001L), ContactId.from("g10001"))
        assertEquals(ContactId.private(20002L), ContactId.from("u20002"))
    }

    @Test
    fun `parsePlatformContact 仅接受持久化命名空间格式`() {
        assertEquals(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "10001"),
            parsePlatformContact("onebot11:group:10001"),
        )
        assertEquals(
            PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, "user-demo"),
            parsePlatformContact("qq_official:private:user-demo"),
        )
        assertNull(parsePlatformContact("group:10001"))
        assertNull(parsePlatformContact("private:20002"))
        assertNull(parsePlatformContact("g10001"))
        assertNull(parsePlatformContact("u20002"))
    }

    @Test
    fun `parseCommandPlatformContact 仍可按显式默认值解析短格式`() {
        assertEquals(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.GROUP, "10001"),
            parseCommandPlatformContact("group:10001", PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE),
        )
        assertEquals(
            PlatformContact(PlatformType.ONEBOT11, PlatformChatType.PRIVATE, "20002"),
            parseCommandPlatformContact("u20002", PlatformType.QQ_OFFICIAL, PlatformChatType.GROUP),
        )
        assertEquals(
            PlatformContact(PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE, "openid-demo"),
            parseCommandPlatformContact("openid-demo", PlatformType.QQ_OFFICIAL, PlatformChatType.PRIVATE),
        )
    }
}
