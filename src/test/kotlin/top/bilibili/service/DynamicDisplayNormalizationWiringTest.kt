package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class DynamicDisplayNormalizationWiringTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `dynamic link drawing should normalize nested article opus display tree before rendering`() {
        val resolve = read("src/main/kotlin/top/bilibili/service/ResolveLinkService.kt")

        assertTrue(
            resolve.contains("normalizeArticleOpusDisplayTree()"),
            "dynamic link drawing should normalize nested forwarded article display before drawDynamic()"
        )
    }
}
