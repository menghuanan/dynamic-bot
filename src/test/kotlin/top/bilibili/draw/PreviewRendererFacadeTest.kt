package top.bilibili.draw

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import top.bilibili.BiliConfig
import top.bilibili.data.DynamicItem
import top.bilibili.data.LiveInfo
import top.bilibili.data.ModuleAuthor
import top.bilibili.data.ModuleDynamic

class PreviewRendererFacadeTest {
    @Test
    fun `render should route dynamic preview requests to dynamic renderer`() = runBlocking {
        val snapshot = RenderSnapshotFactory.fromConfig(BiliConfig())
        var dynamicCalls = 0
        var liveCalls = 0
        val facade = PreviewRendererFacade(
            dynamicRenderer = { _, colors, incomingSnapshot ->
                dynamicCalls++
                assertEquals(listOf(0x112233), colors)
                assertEquals(snapshot, incomingSnapshot)
                "dynamic/path.png"
            },
            liveRenderer = { _, _, _ ->
                liveCalls++
                "live/path.png"
            },
        )

        val result = facade.render(
            PreviewRenderRequest.Dynamic(
                detail = sampleDynamicItem(),
                colors = listOf(0x112233),
                snapshot = snapshot,
            ),
        )

        assertEquals(1, dynamicCalls)
        assertEquals(0, liveCalls)
        assertEquals(PreviewRenderKind.DYNAMIC, result.kind)
        assertEquals("dynamic/path.png", result.outputPath)
        assertTrue(result.durationMs >= 0)
    }

    @Test
    fun `render should route live preview requests to live renderer`() = runBlocking {
        val snapshot = RenderSnapshotFactory.fromConfig(BiliConfig())
        var dynamicCalls = 0
        var liveCalls = 0
        val facade = PreviewRendererFacade(
            dynamicRenderer = { _, _, _ ->
                dynamicCalls++
                "dynamic/path.png"
            },
            liveRenderer = { _, colors, incomingSnapshot ->
                liveCalls++
                assertEquals(listOf(0x445566), colors)
                assertEquals(snapshot, incomingSnapshot)
                "live/path.png"
            },
        )

        val result = facade.render(
            PreviewRenderRequest.Live(
                detail = sampleLiveInfo(),
                colors = listOf(0x445566),
                snapshot = snapshot,
            ),
        )

        assertEquals(0, dynamicCalls)
        assertEquals(1, liveCalls)
        assertEquals(PreviewRenderKind.LIVE, result.kind)
        assertEquals("live/path.png", result.outputPath)
        assertTrue(result.durationMs >= 0)
    }

    private fun sampleDynamicItem(): DynamicItem {
        return DynamicItem(
            typeStr = "DYNAMIC_TYPE_WORD",
            basic = DynamicItem.DynamicBasic(
                commentIdStr = "1",
                commentType = 11,
                ridStr = "1",
            ),
            idStr = "1000",
            modules = DynamicItem.Modules(
                moduleAuthor = sampleAuthor(),
                moduleDynamic = ModuleDynamic(
                    desc = ModuleDynamic.ContentDesc(
                        richTextNodes = emptyList(),
                        text = "hello playground",
                    ),
                ),
            ),
        )
    }

    private fun sampleAuthor(): ModuleAuthor {
        return ModuleAuthor(
            mid = 10086L,
            name = "sample-up",
            face = "https://example.com/face.png",
        )
    }

    private fun sampleLiveInfo(): LiveInfo {
        return LiveInfo(
            title = "sample live",
            roomId = 20000L,
            uid = 10086L,
            uname = "sample-streamer",
            face = "https://example.com/face.png",
            cover = "https://example.com/cover.png",
            liveTimeDuration = 100L,
            liveStatus = 1,
            area = "games",
        )
    }
}