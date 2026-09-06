package com.grayvines.runway.ui.settings

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.search.SearchTarget

@Composable
fun SettingsScreen(
    settings: Settings,
    searchTargets: List<SearchTarget>,
    onChange: ((Settings) -> Settings) -> Unit,
    onOpenHome: (() -> Unit)?,
    debugActions: DebugActions?,
) {
    Scaffold { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text("Runway", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.padding(8.dp))
            if (onOpenHome != null) {
                // Runway is not the home app yet: a way to try it without switching.
                Button(onClick = onOpenHome) { Text("Open home screen") }
            }

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
            SearchTargetPicker(settings.searchTarget, searchTargets) { packageName ->
                onChange { it.copy(searchTarget = packageName) }
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

/** "Search with": a dropdown of Automatic plus every app that can take a web search. */
@Composable
private fun SearchTargetPicker(
    chosen: String?,
    targets: List<SearchTarget>,
    onChoose: (packageName: String?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = targets.firstOrNull { it.packageName == chosen }
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Search with", Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }) {
                current?.let {
                    TargetIcon(it.icon)
                    Spacer(Modifier.width(8.dp))
                }
                Text(current?.label ?: AUTOMATIC)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(AUTOMATIC) },
                    onClick = {
                        open = false
                        onChoose(null)
                    },
                )
                targets.forEach { target ->
                    DropdownMenuItem(
                        text = { Text(target.label) },
                        leadingIcon = { TargetIcon(target.icon) },
                        onClick = {
                            open = false
                            onChoose(target.packageName)
                        },
                    )
                }
            }
        }
    }
    Text(
        "Automatic is Firefox if installed, otherwise the first app that can.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TargetIcon(icon: Drawable) {
    val bitmap = remember(icon) { icon.toBitmap(CHOICE_ICON_PX, CHOICE_ICON_PX).asImageBitmap() }
    Image(bitmap, contentDescription = null, modifier = Modifier.size(24.dp))
}

private const val AUTOMATIC = "Automatic"
private const val CHOICE_ICON_PX = 96

@Composable
private fun Toggle(label: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
