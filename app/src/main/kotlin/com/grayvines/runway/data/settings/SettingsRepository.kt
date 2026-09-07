package com.grayvines.runway.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

class SettingsRepository(context: Context) {
    private val store = context.settingsStore

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> transform(prefs.toSettings()).writeTo(prefs) }
    }

    private object Keys {
        val columns = intPreferencesKey("columns")
        val rows = intPreferencesKey("rows")
        val dockSlots = intPreferencesKey("dock_slots")
        val homeLabels = booleanPreferencesKey("home_labels")
        val dockLabels = booleanPreferencesKey("dock_labels")
        val drawerLabels = booleanPreferencesKey("drawer_labels")
        val searchBarAtTop = booleanPreferencesKey("search_bar_at_top")
        val searchTarget = stringPreferencesKey("search_target")
        val drawerSwipe = stringPreferencesKey("drawer_swipe")
        val drawerKeyboard = booleanPreferencesKey("drawer_keyboard")
        val drawerColumns = intPreferencesKey("drawer_columns")
    }

    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
            columns = this[Keys.columns] ?: defaults.columns,
            rows = this[Keys.rows] ?: defaults.rows,
            dockSlots = this[Keys.dockSlots] ?: defaults.dockSlots,
            homeLabels = this[Keys.homeLabels] ?: defaults.homeLabels,
            dockLabels = this[Keys.dockLabels] ?: defaults.dockLabels,
            drawerLabels = this[Keys.drawerLabels] ?: defaults.drawerLabels,
            searchBarAtTop = this[Keys.searchBarAtTop] ?: defaults.searchBarAtTop,
            searchTarget = this[Keys.searchTarget],
            drawerSwipe =
                this[Keys.drawerSwipe]?.let { name ->
                    DrawerSwipe.entries.firstOrNull { it.name == name }
                } ?: defaults.drawerSwipe,
            drawerKeyboard = this[Keys.drawerKeyboard] ?: defaults.drawerKeyboard,
            drawerColumns = this[Keys.drawerColumns],
        )
    }

    private fun Settings.writeTo(prefs: MutablePreferences) {
        prefs[Keys.columns] = columns
        prefs[Keys.rows] = rows
        prefs[Keys.dockSlots] = dockSlots
        prefs[Keys.homeLabels] = homeLabels
        prefs[Keys.dockLabels] = dockLabels
        prefs[Keys.drawerLabels] = drawerLabels
        prefs[Keys.searchBarAtTop] = searchBarAtTop
        searchTarget?.let { prefs[Keys.searchTarget] = it } ?: prefs.remove(Keys.searchTarget)
        prefs[Keys.drawerSwipe] = drawerSwipe.name
        prefs[Keys.drawerKeyboard] = drawerKeyboard
        drawerColumns?.let { prefs[Keys.drawerColumns] = it } ?: prefs.remove(Keys.drawerColumns)
    }
}
