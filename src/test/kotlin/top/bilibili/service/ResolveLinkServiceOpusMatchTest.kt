package top.bilibili.service

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class ResolveLinkServiceOpusMatchTest {
    @Test
    fun `matchingRegular should keep opus links on the dynamic-detail route`() = runBlocking {
        val result = assertNotNull(matchingRegular("https://www.bilibili.com/opus/1183668934980665366"))

        val type = assertIs<LinkType.OpusWithCv>(result.type)
        assertEquals("1183668934980665366", type.opusId)
        assertEquals(null, type.cvId)
        assertEquals("1183668934980665366", result.id)
    }
}
