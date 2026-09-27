package com.grayvines.runway.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.search.SearchTarget

/**
 * A page of settings. One with a [summary] is on the first page's list; one with a [parent] opens
 * from it, and Back returns there.
 */
private enum class SettingsPage(
    val title: String,
    val summary: String? = null,
    val parent: SettingsPage? = null,
) {
    HOME("Home screen", "Grid, dock and labels"),
    DRAWER("Drawer", "Swipe, keyboard, columns, labels and hidden apps"),
    HIDDEN_APPS("Hidden apps", parent = DRAWER),
    SEARCH_BAR("Search bar", "Where it sits and what it searches with"),
    GESTURES("Gestures", "What a swipe right opens"),
    BACKUP("Backup", "Save or restore the layout and settings"),
    DEBUG("Debug", "Fill or clear the layout"),
}

/** The list of pages, and whichever one of them is open; Back returns to where it opened from. */
@Composable
fun SettingsScreen(
    settings: Settings,
    searchTargets: List<SearchTarget>,
    /** Every launchable app: what a swipe can open, and what can be hidden. */
    apps: List<AppEntry>,
    /** The apps the drawer leaves out. */
    hiddenApps: Set<AppRef>,
    onHide: (app: AppRef, hidden: Boolean) -> Unit,
    onChange: ((Settings) -> Settings) -> Unit,
    onOpenHome: (() -> Unit)?,
    backupActions: BackupActions,
    debugActions: DebugActions?,
) {
    // Saved, so the page stays open across a rotation or the process being let go.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = page?.parent }
    Scaffold(topBar = { Header(page, onBack = { page = page?.parent }) }) { padding ->
        val body = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        when (page) {
            null ->
                Scrolling(padding) {
                    PageList(onOpenHome, debug = debugActions != null) { page = it }
                }
            SettingsPage.HOME -> Scrolling(padding, body) { HomeScreenPage(settings, onChange) }
            SettingsPage.DRAWER ->
                Scrolling(padding, body) {
                    DrawerPage(
                        settings,
                        onChange,
                        hiddenCount = apps.count { it.ref in hiddenApps },
                        onOpenHidden = { page = SettingsPage.HIDDEN_APPS },
                    )
                }
            SettingsPage.HIDDEN_APPS -> HiddenAppsPage(apps, hiddenApps, onHide, padding)
            SettingsPage.SEARCH_BAR ->
                Scrolling(padding, body) { SearchBarPage(settings, searchTargets, onChange) }
            SettingsPage.GESTURES ->
                Scrolling(padding, body) { GesturesPage(settings, apps, onChange) }
            SettingsPage.BACKUP -> Scrolling(padding, body) { BackupPage(backupActions) }
            SettingsPage.DEBUG -> debugActions?.let { Scrolling(padding, body) { DebugPage(it) } }
        }
    }
}

/** A page that scrolls as a whole, from its top, clear of the header by [padding]. */
@Composable
private fun Scrolling(
    padding: PaddingValues,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier.padding(padding).verticalScroll(rememberScrollState()).then(modifier),
        content = content,
    )
}

/** "Runway" over the list; a page's title, with a way back, over a page. */
@Composable
private fun Header(page: SettingsPage?, onBack: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (page == null) {
            Text(
                "Runway",
                Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.headlineMedium,
            )
        } else {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
            Text(page.title, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** Every listed page, title over summary; the debug page only in a debug build. */
@Composable
private fun PageList(onOpenHome: (() -> Unit)?, debug: Boolean, onOpen: (SettingsPage) -> Unit) {
    if (onOpenHome != null) {
        // Runway is not the home app yet: a way to try it without switching.
        Button(onClick = onOpenHome, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("Open home screen")
        }
    }
    SettingsPage.entries
        .filter { debug || it != SettingsPage.DEBUG }
        .forEach { page ->
            page.summary?.let { summary -> PageRow(page.title, summary) { onOpen(page) } }
        }
}
