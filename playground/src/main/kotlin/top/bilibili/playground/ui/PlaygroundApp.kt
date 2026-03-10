package top.bilibili.playground.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import top.bilibili.BiliConfig
import top.bilibili.FooterConfig
import top.bilibili.ImageConfig
import top.bilibili.TemplateConfig
import top.bilibili.draw.PreviewRendererFacade
import top.bilibili.draw.RenderSnapshotFactory
import top.bilibili.draw.RenderSnapshotOverrides
import top.bilibili.playground.fixture.FixtureLoader
import top.bilibili.playground.fixture.PlaygroundFixture
import top.bilibili.playground.state.PlaygroundState
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Composable
fun PlaygroundApp() {
    val facade = remember { PreviewRendererFacade() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PlaygroundState()) }

    LaunchedEffect(Unit) {
        runCatching { FixtureLoader.loadDefaults() }
            .onSuccess { fixtures ->
                state = state.copy(
                    fixtures = fixtures,
                    selectedFixtureId = fixtures.firstOrNull()?.id.orEmpty(),
                    lastError = null,
                )
            }
            .onFailure { error ->
                state = state.copy(lastError = error.message)
            }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ControlsPane(
                    state = state,
                    onStateChange = { state = it },
                    onRender = {
                        val fixture = selectedFixture(state) ?: return@ControlsPane
                        scope.launch {
                            state = state.copy(isRendering = true, lastError = null)
                            runCatching {
                                val snapshot = buildSnapshot(state)
                                val result = facade.render(fixture.toPreviewRequest(snapshot))
                                state.copy(isRendering = false, lastResult = result, lastError = null)
                            }.onSuccess {
                                state = it
                            }.onFailure { error ->
                                state = state.copy(isRendering = false, lastError = error.message)
                            }
                        }
                    },
                    onExport = {
                        val fixture = selectedFixture(state) ?: return@ControlsPane
                        val currentPath = state.lastResult?.outputPath ?: return@ControlsPane
                        runCatching {
                            val target = Path.of("playground-output", "${fixture.id}.png")
                            Files.createDirectories(target.parent)
                            Files.copy(Path.of(currentPath), target, StandardCopyOption.REPLACE_EXISTING)
                            target.toAbsolutePath().toString()
                        }.onSuccess { exportPath ->
                            state = state.copy(lastExportPath = exportPath, lastError = null)
                        }.onFailure { error ->
                            state = state.copy(lastError = error.message)
                        }
                    },
                )

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    PreviewPane(
                        currentImagePath = state.lastResult?.outputPath,
                        baselineImagePath = state.baselinePath.ifBlank { null },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DiagnosticsPane(
                        state = state,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun selectedFixture(state: PlaygroundState): PlaygroundFixture? {
    return state.fixtures.firstOrNull { it.id == state.selectedFixtureId }
}

private fun buildSnapshot(state: PlaygroundState) = RenderSnapshotFactory.fromConfig(
    config = BiliConfig(
        imageConfig = ImageConfig(
            quality = state.selectedQualityKey,
            theme = state.selectedThemeKey,
            font = state.fontFamily,
            cardOrnament = state.cardOrnament,
            badgeEnable = ImageConfig.BadgeEnable(
                left = state.badgeLeftEnabled,
                right = state.badgeRightEnabled,
            ),
        ),
        templateConfig = TemplateConfig(
            footer = FooterConfig(
                footerAlign = state.footerAlign,
            ),
        ),
    ),
    overrides = RenderSnapshotOverrides(
        fontFamily = state.fontFamily.ifBlank { null },
        footerAlign = state.footerAlign,
        cardOrnament = state.cardOrnament,
        badgeLeftEnabled = state.badgeLeftEnabled,
        badgeRightEnabled = state.badgeRightEnabled,
    ),
)