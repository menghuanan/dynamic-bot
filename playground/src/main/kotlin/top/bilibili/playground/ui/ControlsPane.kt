package top.bilibili.playground.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.RadioButton
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.bilibili.playground.fixture.PlaygroundFixture
import top.bilibili.playground.state.PlaygroundState

@Composable
fun ControlsPane(
    state: PlaygroundState,
    onStateChange: (PlaygroundState) -> Unit,
    onFixtureSelected: (PlaygroundFixture) -> Unit,
    onRender: () -> Unit,
    onLoadFixtureFile: () -> Unit,
    onSaveFixtureFile: () -> Unit,
    onExport: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(420.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Fixtures")
        state.fixtures.forEach { fixture ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RadioButton(
                    selected = state.selectedFixtureId == fixture.id,
                    onClick = { onFixtureSelected(fixture) },
                )
                Text(fixture.label)
            }
        }

        Text("Editable Fixture JSON")
        OutlinedTextField(
            value = state.fixtureJson,
            onValueChange = { onStateChange(state.copy(fixtureJson = it)) },
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp),
            minLines = 18,
            maxLines = 24,
            label = { Text("Fixture JSON") },
        )

        Text("Fixture file")
        OutlinedTextField(
            value = state.fixtureFilePath,
            onValueChange = { onStateChange(state.copy(fixtureFilePath = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Fixture path") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onLoadFixtureFile,
                enabled = state.fixtureFilePath.isNotBlank() && !state.isRendering,
            ) {
                Text("Load fixture file")
            }
            Button(
                onClick = onSaveFixtureFile,
                enabled = state.fixtureJson.isNotBlank() && !state.isRendering,
            ) {
                Text("Save fixture file")
            }
        }

        OutlinedTextField(
            value = state.selectedQualityKey,
            onValueChange = { onStateChange(state.copy(selectedQualityKey = it)) },
            label = { Text("Quality key") },
        )
        OutlinedTextField(
            value = state.selectedThemeKey,
            onValueChange = { onStateChange(state.copy(selectedThemeKey = it)) },
            label = { Text("Theme key") },
        )
        OutlinedTextField(
            value = state.fontFamily,
            onValueChange = { onStateChange(state.copy(fontFamily = it)) },
            label = { Text("Font family") },
        )
        OutlinedTextField(
            value = state.cardOrnament,
            onValueChange = { onStateChange(state.copy(cardOrnament = it)) },
            label = { Text("Card ornament") },
        )
        OutlinedTextField(
            value = state.footerAlign,
            onValueChange = { onStateChange(state.copy(footerAlign = it.uppercase())) },
            label = { Text("Footer align") },
        )
        OutlinedTextField(
            value = state.baselinePath,
            onValueChange = { onStateChange(state.copy(baselinePath = it)) },
            label = { Text("Baseline path") },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = state.badgeLeftEnabled,
                onCheckedChange = { checked -> onStateChange(state.copy(badgeLeftEnabled = checked)) },
            )
            Text("Left badge")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Checkbox(
                checked = state.badgeRightEnabled,
                onCheckedChange = { checked -> onStateChange(state.copy(badgeRightEnabled = checked)) },
            )
            Text("Right badge")
        }

        Button(onClick = onRender, enabled = state.fixtureJson.isNotBlank() && !state.isRendering) {
            Text(if (state.isRendering) "Rendering..." else "Render preview")
        }
        Button(onClick = onExport, enabled = state.lastResult != null) {
            Text("Export current PNG")
        }
    }
}