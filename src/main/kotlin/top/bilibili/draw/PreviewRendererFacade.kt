package top.bilibili.draw

import top.bilibili.data.DynamicItem
import top.bilibili.data.LiveInfo
import kotlin.system.measureTimeMillis

enum class PreviewRenderKind {
    DYNAMIC,
    LIVE,
}

sealed interface PreviewRenderRequest {
    val colors: List<Int>
    val snapshot: RenderSnapshot
    data class Dynamic(
        val detail: DynamicItem,
        override val colors: List<Int>,
        override val snapshot: RenderSnapshot,
    ) : PreviewRenderRequest

    data class Live(
        val detail: LiveInfo,
        override val colors: List<Int>,
        override val snapshot: RenderSnapshot,
    ) : PreviewRenderRequest
}

data class PreviewRenderResult(
    val kind: PreviewRenderKind,
    val outputPath: String,
    val durationMs: Long,
)

class PreviewRendererFacade(
    private val dynamicRenderer: suspend (DynamicItem, List<Int>, RenderSnapshot) -> String = { detail, colors, snapshot ->
        withRenderSnapshot(snapshot) {
            detail.makeDrawDynamic(colors)
        }
    },
    private val liveRenderer: suspend (LiveInfo, List<Int>, RenderSnapshot) -> String = { detail, colors, snapshot ->
        withRenderSnapshot(snapshot) {
            detail.makeDrawLive(colors)
        }
    },
) {
    suspend fun render(request: PreviewRenderRequest): PreviewRenderResult {
        var outputPath = ""
        val durationMs = measureTimeMillis {
            outputPath = when (request) {
                is PreviewRenderRequest.Dynamic -> dynamicRenderer(request.detail, request.colors, request.snapshot)
                is PreviewRenderRequest.Live -> liveRenderer(request.detail, request.colors, request.snapshot)
            }
        }

        return PreviewRenderResult(
            kind = when (request) {
                is PreviewRenderRequest.Dynamic -> PreviewRenderKind.DYNAMIC
                is PreviewRenderRequest.Live -> PreviewRenderKind.LIVE
            },
            outputPath = outputPath,
            durationMs = durationMs,
        )
    }
}