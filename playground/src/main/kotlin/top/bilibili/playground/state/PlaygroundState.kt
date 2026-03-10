package top.bilibili.playground.state

import top.bilibili.draw.PreviewRenderResult
import top.bilibili.playground.fixture.PlaygroundFixture

data class PlaygroundState(
    val fixtures: List<PlaygroundFixture> = emptyList(),
    val selectedFixtureId: String = "",
    val fixtureJson: String = "",
    val fixtureFilePath: String = "",
    val selectedQualityKey: String = "1000w",
    val selectedThemeKey: String = "v3",
    val fontFamily: String = "",
    val cardOrnament: String = "FanCard",
    val badgeLeftEnabled: Boolean = true,
    val badgeRightEnabled: Boolean = false,
    val footerAlign: String = "LEFT",
    val baselinePath: String = "",
    val lastResult: PreviewRenderResult? = null,
    val lastError: String? = null,
    val lastExportPath: String? = null,
    val isRendering: Boolean = false,
)