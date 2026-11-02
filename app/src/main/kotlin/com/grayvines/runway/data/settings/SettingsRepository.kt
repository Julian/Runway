package com.grayvines.runway.data.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

/**
 * What to make of a settings file that cannot be read: nothing, and start over. The settings are a
 * convenience; a launcher that will not come up because of them is not.
 */
internal val settingsCorruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }

internal val Context.settingsStore: DataStore<Preferences> by
    preferencesDataStore("settings", corruptionHandler = settingsCorruptionHandler)

class SettingsRepository internal constructor(private val store: DataStore<Preferences>) {
    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    constructor(context: Context) : this(context.settingsStore)

    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { prefs -> transform(prefs.toSettings()).clamped().writeTo(prefs) }
    }

    private object Keys {
        val columns = intPreferencesKey("columns")
        val rows = intPreferencesKey("rows")
        val dockSlots = intPreferencesKey("dock_slots")
        val homeLabels = booleanPreferencesKey("home_labels")
        val dockLabels = booleanPreferencesKey("dock_labels")
        val drawerLabels = booleanPreferencesKey("drawer_labels")
        val badgeStyle = stringPreferencesKey("badge_style")
        val searchBarAtTop = booleanPreferencesKey("search_bar_at_top")
        val searchTarget = stringPreferencesKey("search_target")
        val drawerSwipe = stringPreferencesKey("drawer_swipe")
        val drawerKeyboard = booleanPreferencesKey("drawer_keyboard")
        val drawerColumns = intPreferencesKey("drawer_columns")
        val drawerIndex = booleanPreferencesKey("drawer_index")
        val searchHiddenApps = booleanPreferencesKey("search_hidden_apps")
        val wallpaperParallax = booleanPreferencesKey("wallpaper_parallax")
        /** The action as JSON: one key holds any kind of action, with whatever it names. */
        val swipeRight = stringPreferencesKey("swipe_right")
    }

    /** What the store holds, within bounds: nothing on disk is trusted to be. */
    private fun Preferences.toSettings(): Settings {
        val defaults = Settings()
        return Settings(
                columns = getOrDefault(Keys.columns, defaults.columns),
                rows = getOrDefault(Keys.rows, defaults.rows),
                dockSlots = getOrDefault(Keys.dockSlots, defaults.dockSlots),
                homeLabels = getOrDefault(Keys.homeLabels, defaults.homeLabels),
                dockLabels = getOrDefault(Keys.dockLabels, defaults.dockLabels),
                drawerLabels = getOrDefault(Keys.drawerLabels, defaults.drawerLabels),
                badgeStyle = enumOrDefault(Keys.badgeStyle, defaults.badgeStyle),
                searchBarAtTop = getOrDefault(Keys.searchBarAtTop, defaults.searchBarAtTop),
                searchTarget = this[Keys.searchTarget],
                drawerSwipe = enumOrDefault(Keys.drawerSwipe, defaults.drawerSwipe),
                drawerKeyboard = getOrDefault(Keys.drawerKeyboard, defaults.drawerKeyboard),
                drawerColumns = this[Keys.drawerColumns],
                drawerIndex = getOrDefault(Keys.drawerIndex, defaults.drawerIndex),
                searchHiddenApps = getOrDefault(Keys.searchHiddenApps, defaults.searchHiddenApps),
                wallpaperParallax =
                    getOrDefault(Keys.wallpaperParallax, defaults.wallpaperParallax),
                swipeRight = this[Keys.swipeRight]?.let(::swipeActionOrNull),
            )
            .clamped()
    }

    private fun Settings.writeTo(prefs: MutablePreferences) {
        prefs[Keys.columns] = columns
        prefs[Keys.rows] = rows
        prefs[Keys.dockSlots] = dockSlots
        prefs[Keys.homeLabels] = homeLabels
        prefs[Keys.dockLabels] = dockLabels
        prefs[Keys.drawerLabels] = drawerLabels
        prefs[Keys.badgeStyle] = badgeStyle.name
        prefs[Keys.searchBarAtTop] = searchBarAtTop
        searchTarget?.let { prefs[Keys.searchTarget] = it } ?: prefs.remove(Keys.searchTarget)
        prefs[Keys.drawerSwipe] = drawerSwipe.name
        prefs[Keys.drawerKeyboard] = drawerKeyboard
        drawerColumns?.let { prefs[Keys.drawerColumns] = it } ?: prefs.remove(Keys.drawerColumns)
        prefs[Keys.drawerIndex] = drawerIndex
        prefs[Keys.searchHiddenApps] = searchHiddenApps
        prefs[Keys.wallpaperParallax] = wallpaperParallax
        swipeRight?.let { prefs[Keys.swipeRight] = Json.encodeToString<SwipeAction>(it) }
            ?: prefs.remove(Keys.swipeRight)
    }
}

/**
 * The action [json] describes, or null for one this build cannot read (written by a newer Runway,
 * or edited by hand): a swipe that does nothing, rather than settings that cannot be read.
 */
private fun swipeActionOrNull(json: String): SwipeAction? =
    try {
        Json.decodeFromString<SwipeAction>(json)
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "a swipe action this build cannot read; it does nothing", e)
        null
    }

internal fun <T> Preferences.getOrDefault(key: Preferences.Key<T>, default: T): T =
    this[key] ?: default

private inline fun <reified E : Enum<E>> Preferences.enumOrDefault(
    key: Preferences.Key<String>,
    default: E,
): E = this[key]?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

private const val TAG = "Runway"
