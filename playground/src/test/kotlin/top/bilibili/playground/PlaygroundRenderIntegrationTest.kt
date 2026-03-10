package top.bilibili.playground

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue
import top.bilibili.BiliConfigManager
import top.bilibili.draw.PreviewRendererFacade
import top.bilibili.draw.RenderSnapshotFactory
import top.bilibili.playground.fixture.FixtureLoader

class PlaygroundRenderIntegrationTest {
    @Test
    fun `bundled fixtures should render through production facade`() = runBlocking {
        PlaygroundBootstrap.ensureInitialized()

        val fixtures = FixtureLoader.loadDefaults()
        val facade = PreviewRendererFacade()
        val snapshot = RenderSnapshotFactory.fromConfig(BiliConfigManager.config)

        fixtures.forEach { fixture ->
            val result = facade.render(fixture.toPreviewRequest(snapshot))
            assertTrue(File(result.outputPath).exists(), "expected rendered image for ${fixture.id} at ${result.outputPath}")
        }
    }
}