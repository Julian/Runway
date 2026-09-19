package com.grayvines.runway.ui.settings

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.search.SearchTarget

/**
 * The grid's steppers, and whether its icons are labelled. Each step is applied to the stored
 * value, not the one on screen: the screen can be a write behind (the first frames show defaults;
 * two quick taps show one).
 */
@Composable
internal fun HomeScreenPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    Section("Grid")
    Stepper("Columns", settings.columns, Settings.MIN_COLUMNS, Settings.MAX_COLUMNS) { by ->
        onChange {
            it.copy(columns = it.columns.stepped(by, Settings.MIN_COLUMNS, Settings.MAX_COLUMNS))
        }
    }
    Stepper("Rows", settings.rows, Settings.MIN_ROWS, Settings.MAX_ROWS) { by ->
        onChange {
            it.copy(rows = it.rows.stepped(by, Settings.MIN_ROWS, Settings.MAX_ROWS))
        }
    }
    Stepper(
        "Dock slots",
        settings.dockSlots,
        Settings.MIN_DOCK_SLOTS,
        Settings.MAX_DOCK_SLOTS,
    ) { by ->
        onChange {
            it.copy(
                dockSlots =
                    it.dockSlots.stepped(
                        by,
                        Settings.MIN_DOCK_SLOTS,
                        Settings.MAX_DOCK_SLOTS,
                    )
            )
        }
    }

    Section("Labels")
    Toggle("Home", settings.homeLabels) { v -> onChange { it.copy(homeLabels = v) } }
    Toggle("Dock", settings.dockLabels) { v -> onChange { it.copy(dockLabels = v) } }
}

@Composable
internal fun DrawerPage(settings: Settings, onChange: ((Settings) -> Settings) -> Unit) {
    OptionRow(
        label = "Swipe sensitivity",
        current = settings.drawerSwipe.label,
        options = DrawerSwipe.entries.map { it.label },
    ) { picked ->
        onChange { it.copy(drawerSwipe = DrawerSwipe.entries.first { e -> e.label == picked }) }
    }
    Toggle("Keyboard when opening", settings.drawerKeyboard) { v ->
        onChange { it.copy(drawerKeyboard = v) }
    }
    Toggle("Same columns as home", settings.drawerColumns == null) { same ->
        onChange { it.copy(drawerColumns = if (same) null else it.columns) }
    }
    settings.drawerColumns?.let { drawerColumns ->
        Stepper("Columns", drawerColumns, Settings.MIN_COLUMNS, Settings.MAX_COLUMNS) { by ->
            onChange {
                it.copy(
                    drawerColumns =
                        (it.drawerColumns ?: it.columns).stepped(
                            by,
                            Settings.MIN_COLUMNS,
                            Settings.MAX_COLUMNS,
                        )
                )
            }
        }
    }
    Toggle("Alphabet along the edge", settings.drawerIndex) { v ->
        onChange { it.copy(drawerIndex = v) }
    }
    Toggle("Labels", settings.drawerLabels) { v -> onChange { it.copy(drawerLabels = v) } }
}

@Composable
internal fun SearchBarPage(
    settings: Settings,
    searchTargets: List<SearchTarget>,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    Toggle("At the top (otherwise above the dock)", settings.searchBarAtTop) { v ->
        onChange { it.copy(searchBarAtTop = v) }
    }
    SearchTargetPicker(settings.searchTarget, searchTargets) { packageName ->
        onChange { it.copy(searchTarget = packageName) }
    }
}

@Composable
internal fun GesturesPage(
    settings: Settings,
    /** Every launchable app, for what a swipe can open. */
    apps: List<AppEntry>,
    onChange: ((Settings) -> Settings) -> Unit,
) {
    Section("Swipe right")
    SwipeRightPicker(settings.swipeRight, apps) { action ->
        onChange { it.copy(swipeRight = action) }
    }
}

@Composable
internal fun BackupPage(actions: BackupActions) {
    Row {
        Button(onClick = actions.save) { Text("Save a backup") }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(onClick = actions.restore) { Text("Restore a backup") }
    }
}

@Composable
internal fun DebugPage(actions: DebugActions) {
    Button(onClick = actions.fillWithAllApps) { Text("Fill with all apps") }
    Button(onClick = actions.clearLayout) { Text("Clear layout") }
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
