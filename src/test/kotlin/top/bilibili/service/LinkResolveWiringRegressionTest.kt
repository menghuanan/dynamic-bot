package top.bilibili.service

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class LinkResolveWiringRegressionTest {
    private fun read(path: String): String = Files.readString(Path.of(path), StandardCharsets.UTF_8)

    @Test
    fun `link resolve path should gate drawing and keep text fallback`() {
        val resolve = read("src/main/kotlin/top/bilibili/service/ResolveLinkService.kt")
        val listener = read("src/main/kotlin/top/bilibili/tasker/ListenerTasker.kt")

        assertTrue(resolve.contains("FeatureSwitchService.canRenderLinkResolveDraw"))
        assertTrue(listener.contains("MessageSegment.text(standardLink)"))
    }
}
