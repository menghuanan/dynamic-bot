package top.bilibili.playground.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.bilibili.playground.state.PlaygroundState

@Composable
fun DiagnosticsPane(
    state: PlaygroundState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Diagnostics")
        Text("Fixture: ${state.selectedFixtureId.ifBlank { "<none>" }}")
        Text("Fixture file: ${state.fixtureFilePath.ifBlank { "<none>" }}")
        Text("JSON chars: ${state.fixtureJson.length}")
        Text("Result: ${state.lastResult?.outputPath ?: "<none>"}")
        Text("Duration: ${state.lastResult?.durationMs ?: 0} ms")
        Text("Export: ${state.lastExportPath ?: "<none>"}")
        Text("Error: ${state.lastError ?: "<none>"}")
    }
}