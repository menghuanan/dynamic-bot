package top.bilibili.playground.fixture

import kotlinx.serialization.Serializable
import top.bilibili.data.DynamicItem
import top.bilibili.data.LiveInfo
import top.bilibili.draw.PreviewRenderRequest
import top.bilibili.draw.RenderSnapshot

@Serializable
enum class PlaygroundFixtureKind {
    DYNAMIC,
    LIVE,
}

@Serializable
data class PlaygroundFixture(
    val id: String,
    val label: String,
    val kind: PlaygroundFixtureKind,
    val colors: List<Int>,
    val dynamic: DynamicItem? = null,
    val live: LiveInfo? = null,
) {
    fun toPreviewRequest(snapshot: RenderSnapshot): PreviewRenderRequest {
        return when (kind) {
            PlaygroundFixtureKind.DYNAMIC -> PreviewRenderRequest.Dynamic(
                detail = requireNotNull(dynamic) { "Dynamic fixture '$id' is missing detail payload" },
                colors = colors,
                snapshot = snapshot,
            )
            PlaygroundFixtureKind.LIVE -> PreviewRenderRequest.Live(
                detail = requireNotNull(live) { "Live fixture '$id' is missing detail payload" },
                colors = colors,
                snapshot = snapshot,
            )
        }
    }
}