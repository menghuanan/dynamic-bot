package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinkResolveOpusRoutingRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `resolve link service should stop downgrading opus data source to article and stop fetching html`() {
        val resolve = read("src/main/kotlin/top/bilibili/service/ResolveLinkService.kt")

        assertTrue(resolve.contains("LinkType.OpusWithCv"), "opus links should keep a dedicated resolved type")
        assertTrue(resolve.contains("getArticleView"), "cv links should use the article view API to resolve dyn_id_str")
        assertFalse(
            resolve.contains("return ResolvedLinkInfo(LinkType.Article, cvId, subject)"),
            "opus links must no longer switch data source to article detail"
        )
        assertFalse(
            resolve.contains("it.get(opusUrl).body<String>()"),
            "opus routing should no longer fetch article html just to discover cv id"
        )
        assertFalse(
            resolve.contains("resolveOpusCvId("),
            "legacy html-based cv extraction should be removed from the link-resolve path"
        )
    }
}
