package com.grayvines.runway.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.settings.Settings

@Composable
fun SettingsScreen(
    settings: Settings,
    onChange: ((Settings) -> Settings) -> Unit,
    debugActions: DebugActions?,
) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Runway", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.padding(8.dp))

            Section("Grid")
            Stepper("Columns", settings.columns, Settings.MIN_COLUMNS, Settings.MAX_COLUMNS) { v ->
                onChange { it.copy(columns = v) }
            }
            Stepper("Rows", settings.rows, Settings.MIN_ROWS, Settings.MAX_ROWS) { v ->
                onChange { it.copy(rows = v) }
            }
            Stepper(
                "Dock slots",
                settings.dockSlots,
                Settings.MIN_DOCK_SLOTS,
                Settings.MAX_DOCK_SLOTS,
            ) { v ->
                onChange { it.copy(dockSlots = v) }
            }

            Section("Labels")
            Toggle("Home", settings.homeLabels) { v -> onChange { it.copy(homeLabels = v) } }
            Toggle("Dock", settings.dockLabels) { v -> onChange { it.copy(dockLabels = v) } }
            Toggle("Drawer", settings.drawerLabels) { v -> onChange { it.copy(drawerLabels = v) } }

            Section("Search bar")
            Toggle("At the top (otherwise above the dock)", settings.searchBarAtTop) { v ->
                onChange { it.copy(searchBarAtTop = v) }
            }

            if (debugActions != null) {
                Section("Debug")
                Button(onClick = debugActions.fillWithAllApps) { Text("Fill with all apps") }
                Button(onClick = debugActions.clearLayout) { Text("Clear layout") }
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onValue(value - 1) }, enabled = value > min) { Text("−") }
        Text(
            value.toString(),
            Modifier.width(32.dp),
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = { onValue(value + 1) }, enabled = value < max) { Text("+") }
    }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
