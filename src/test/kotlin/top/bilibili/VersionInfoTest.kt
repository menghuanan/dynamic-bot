package top.bilibili

import kotlin.test.Test
import kotlin.test.assertEquals

class VersionInfoTest {
    @Test
    fun `current version label should prefer explicit system version`() {
        assertEquals("v1.7.2", currentVersionLabel(systemVersion = "1.7.2", implementationVersion = "1.7.1"))
    }

    @Test
    fun `current version label should fall back to implementation version`() {
        assertEquals("v1.7.2", currentVersionLabel(systemVersion = null, implementationVersion = "1.7.2"))
    }

    @Test
    fun `current version label should report unknown when both sources are missing`() {
        assertEquals("unknown", currentVersionLabel(systemVersion = null, implementationVersion = null))
    }
}