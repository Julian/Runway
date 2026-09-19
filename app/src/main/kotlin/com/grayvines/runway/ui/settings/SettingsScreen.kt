package com.grayvines.runway.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.search.SearchTarget

/** A page of settings, as the first page lists it. */
private enum class SettingsPage(val title: String, val summary: String) {
    HOME("Home screen", "Grid, dock and labels"),
    DRAWER("Drawer", "Swipe, keyboard, columns and labels"),
    SEARCH_BAR("Search bar", "Where it sits and what it searches with"),
    GESTURES("Gestures", "What a swipe right opens"),
    BACKUP("Backup", "Save or restore the layout and settings"),
    DEBUG("Debug", "Fill or clear the layout"),
}

/** The list of pages, and whichever one of them is open; Back returns to the list. */
@Composable
fun SettingsScreen(
    settings: Settings,
    searchTargets: List<SearchTarget>,
    /** Every launchable app, for what a swipe can open. */
    apps: List<AppEntry>,
    onChange: ((Settings) -> Settings) -> Unit,
    onOpenHome: (() -> Unit)?,
    backupActions: BackupActions,
    debugActions: DebugActions?,
) {
    // Saved, so the page stays open across a rotation or the process being let go.
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    BackHandler(enabled = page != null) { page = null }
    Scaffold(topBar = { Header(page, onBack = { page = null }) }) { padding ->
        // Each page scrolls on its own, from its top.
        val open = page
        key(open) {
            Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
                if (open == null) {
                    PageList(onOpenHome, debug = debugActions != null) { page = it }
                } else {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        when (open) {
                            SettingsPage.HOME -> HomeScreenPage(settings, onChange)
                            SettingsPage.DRAWER -> DrawerPage(settings, onChange)
                            SettingsPage.SEARCH_BAR ->
                                SearchBarPage(settings, searchTargets, onChange)
                            SettingsPage.GESTURES -> GesturesPage(settings, apps, onChange)
                            SettingsPage.BACKUP -> BackupPage(backupActions)
                            SettingsPage.DEBUG -> debugActions?.let { DebugPage(it) }
                        }
                    }
                }
            }
        }
    }
}

/** "Runway" over the list; a page's title, with a way back to the list, over a page. */
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

/** Every page, title over summary; the debug page only in a debug build. */
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
            Column(
                Modifier.fillMaxWidth()
                    .clickable { onOpen(page) }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(page.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    page.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
}
