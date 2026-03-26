package top.bilibili.core

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
