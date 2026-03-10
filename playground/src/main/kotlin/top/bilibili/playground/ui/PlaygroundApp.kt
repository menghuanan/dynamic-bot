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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.launch
import top.bilibili.BiliConfig
import top.bilibili.FooterConfig
import top.bilibili.ImageConfig
import top.bilibili.TemplateConfig
import top.bilibili.draw.PreviewRendererFacade
import top.bilibili.draw.RenderSnapshotFactory
import top.bilibili.draw.RenderSnapshotOverrides
import top.bilibili.playground.PlaygroundBootstrap
import top.bilibili.playground.fixture.FixtureLoader
import top.bilibili.playground.fixture.PlaygroundFixture
import top.bilibili.playground.state.PlaygroundState

@Composable
fun PlaygroundApp() {
    val facade = remember { PreviewRendererFacade() }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(PlaygroundState()) }

    suspend fun renderFromState(baseState: PlaygroundState): PlaygroundState {
        val fixture = FixtureLoader.decode(baseState.fixtureJson)
        val snapshot = buildSnapshot(baseState)
        val result = facade.render(fixture.toPreviewRequest(snapshot))
        return baseState.copy(
            selectedFixtureId = fixture.id,
            fixtureFilePath = baseState.fixtureFilePath.ifBlank { defaultFixturePath(fixture.id) },
            isRendering = false,
            lastResult = result,
            lastError = null,
        )
    }

    fun selectFixture(currentState: PlaygroundState, fixture: PlaygroundFixture): PlaygroundState {
        return currentState.copy(
            selectedFixtureId = fixture.id,
            fixtureJson = FixtureLoader.encode(fixture),
            fixtureFilePath = defaultFixturePath(fixture.id),
            lastError = null,
        )
    }

    fun upsertFixture(fixtures: List<PlaygroundFixture>, fixture: PlaygroundFixture): List<PlaygroundFixture> {
        val index = fixtures.indexOfFirst { it.id == fixture.id }
        return if (index == -1) fixtures + fixture else fixtures.toMutableList().also { it[index] = fixture }
    }

    fun renderAsync(nextState: PlaygroundState) {
        scope.launch {
            val renderingState = nextState.copy(isRendering = true, lastError = null)
            state = renderingState
            runCatching {
                renderFromState(renderingState)
            }.onSuccess { renderedState ->
                state = renderedState
            }.onFailure { error ->
                state = renderingState.copy(
                    isRendering = false,
                    lastError = error.message ?: error::class.simpleName,
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        state = state.copy(isRendering = true, lastError = null)
        runCatching {
            PlaygroundBootstrap.ensureInitialized()
            FixtureLoader.loadDefaults()
        }.onSuccess { fixtures ->
            val firstFixture = fixtures.firstOrNull()
            val seededState = if (firstFixture != null) {
                selectFixture(state.copy(fixtures = fixtures, isRendering = true), firstFixture)
            } else {
                state.copy(fixtures = fixtures, isRendering = false)
            }
            state = seededState
            if (seededState.fixtureJson.isBlank()) {
                state = seededState.copy(isRendering = false)
                return@onSuccess
            }
            runCatching {
                renderFromState(seededState)
            }.onSuccess { renderedState ->
                state = renderedState
            }.onFailure { error ->
                state = seededState.copy(
                    isRendering = false,
                    lastError = error.message ?: error::class.simpleName,
                )
            }
        }.onFailure { error ->
            state = state.copy(
                isRendering = false,
                lastError = error.message ?: error::class.simpleName,
            )
        }
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ControlsPane(
                    state = state,
                    onStateChange = { state = it },
                    onFixtureSelected = { fixture -> renderAsync(selectFixture(state, fixture)) },
                    onRender = { renderAsync(state) },
                    onLoadFixtureFile = {
                        val pathText = state.fixtureFilePath.ifBlank { return@ControlsPane }
                        runCatching {
                            val path = Path.of(pathText)
                            val fixture = FixtureLoader.loadPath(path)
                            path to fixture
                        }.onSuccess { (path, fixture) ->
                            renderAsync(
                                state.copy(
                                    fixtures = upsertFixture(state.fixtures, fixture),
                                    selectedFixtureId = fixture.id,
                                    fixtureJson = FixtureLoader.encode(fixture),
                                    fixtureFilePath = path.toString(),
                                    lastError = null,
                                ),
                            )
                        }.onFailure { error ->
                            state = state.copy(lastError = error.message ?: error::class.simpleName)
                        }
                    },
                    onSaveFixtureFile = {
                        runCatching {
                            val fixture = FixtureLoader.decode(state.fixtureJson)
                            val target = Path.of(state.fixtureFilePath.ifBlank { defaultFixturePath(fixture.id) })
                            FixtureLoader.savePath(target, fixture)
                            target.toString() to fixture.id
                        }.onSuccess { (savedPath, fixtureId) ->
                            state = state.copy(
                                selectedFixtureId = fixtureId,
                                fixtureFilePath = savedPath,
                                lastError = null,
                            )
                        }.onFailure { error ->
                            state = state.copy(lastError = error.message ?: error::class.simpleName)
                        }
                    },
                    onExport = {
                        val currentPath = state.lastResult?.outputPath ?: return@ControlsPane
                        val currentFixtureId = runCatching {
                            FixtureLoader.decode(state.fixtureJson).id
                        }.getOrDefault(state.selectedFixtureId.ifBlank { "preview" })
                        runCatching {
                            val target = Path.of("playground-output", "$currentFixtureId.png")
                            Files.createDirectories(target.parent)
                            Files.copy(Path.of(currentPath), target, StandardCopyOption.REPLACE_EXISTING)
                            target.toAbsolutePath().toString()
                        }.onSuccess { exportPath ->
                            state = state.copy(lastExportPath = exportPath, lastError = null)
                        }.onFailure { error ->
                            state = state.copy(lastError = error.message ?: error::class.simpleName)
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

private fun defaultFixturePath(fixtureId: String): String {
    return Path.of("playground-fixtures", "$fixtureId.json").toString()
}