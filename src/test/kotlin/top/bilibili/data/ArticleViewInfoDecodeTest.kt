package top.bilibili.data

import kotlinx.serialization.decodeFromString
import top.bilibili.utils.json
import kotlin.test.Test
import kotlin.test.assertEquals

class ArticleViewInfoDecodeTest {
    @Test
    fun `article view payload should decode dyn_id_str for opus redirect`() {
        val payload = """
            {
              "dyn_id_str": "1183668934980665366"
            }
        """.trimIndent()

        val decoded = json.decodeFromString<ArticleViewInfo>(payload)

        assertEquals("1183668934980665366", decoded.dynIdStr)
    }
}
