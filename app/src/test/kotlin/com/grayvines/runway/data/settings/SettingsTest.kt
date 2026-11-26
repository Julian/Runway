package com.grayvines.runway.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.grayvines.runway.data.AppRef
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SettingsTest {
    @TempDir lateinit var tempDir: File

    @Test
    fun `clamping brings every grid number within the settings screen's bounds`() {
        val clamped = Settings(columns = 1, rows = 2, dockSlots = 99, drawerColumns = 0).clamped()
        assertEquals(Settings.MIN_COLUMNS, clamped.columns)
        assertEquals(Settings.MIN_ROWS, clamped.rows)
        assertEquals(Settings.MAX_DOCK_SLOTS, clamped.dockSlots)
        assertEquals(Settings.MIN_COLUMNS, clamped.drawerColumns)
    }

    @Test
    fun `clamping leaves settings within bounds, and an unset drawer width, alone`() {
        val settings = Settings(columns = 5, rows = 8, dockSlots = 4, drawerColumns = null)
        assertEquals(settings, settings.clamped())
    }

    @Test
    fun `repository round-trips every setting, clamps on write, and clears unset optionals`() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = settingsCorruptionHandler,
                    scope = backgroundScope,
                ) {
                    File(tempDir, "settings.preferences_pb")
                }
            val repo = SettingsRepository(store)
            assertEquals(Settings(), repo.settings.first())

            val custom =
                Settings(
                    columns = 5,
                    rows = 9,
                    dockSlots = 4,
                    homeLabels = true,
                    dockLabels = true,
                    drawerLabels = false,
                    iconShape = IconShape.SQUIRCLE,
                    badgeStyle = BadgeStyle.DOT,
                    searchBarAtTop = false,
                    searchTarget = "org.mozilla.firefox",
                    drawerSwipe = DrawerSwipe.HIGH,
                    drawerKeyboard = false,
                    drawerColumns = 6,
                    drawerIndex = true,
                    searchHiddenApps = true,
                    wallpaperParallax = false,
                    swipeRight = SwipeAction.OpenApp(AppRef("pkg/.Main", 10)),
                )
            repo.update { custom }
            assertEquals(custom, repo.settings.first())

            // Out-of-bounds updates are clamped before writing to disk, and cleared optionals go.
            repo.update {
                it.copy(
                    columns = 99,
                    rows = 1,
                    dockSlots = 0,
                    searchTarget = null,
                    drawerColumns = null,
                    swipeRight = null,
                )
            }
            assertEquals(Settings.MAX_COLUMNS, store.data.first()[intPreferencesKey("columns")])
            assertEquals(Settings.MIN_ROWS, store.data.first()[intPreferencesKey("rows")])
            assertEquals(
                Settings.MIN_DOCK_SLOTS,
                store.data.first()[intPreferencesKey("dock_slots")],
            )
            assertEquals(
                custom.copy(
                    columns = Settings.MAX_COLUMNS,
                    rows = Settings.MIN_ROWS,
                    dockSlots = Settings.MIN_DOCK_SLOTS,
                    searchTarget = null,
                    drawerColumns = null,
                    swipeRight = null,
                ),
                repo.settings.first(),
            )
        }

    @Test
    fun `clamping turns a blank search target into null`() {
        assertNull(Settings(searchTarget = "").clamped().searchTarget)
        assertNull(Settings(searchTarget = "   ").clamped().searchTarget)
        assertEquals(
            "org.mozilla.firefox",
            Settings(searchTarget = " org.mozilla.firefox ").clamped().searchTarget,
        )
    }

    @Test
    fun `IOException reading preferences falls back to default settings`() = runTest {
        val failingStore =
            object : DataStore<Preferences> {
                override val data: Flow<Preferences> = flow {
                    throw IOException("disk unavailable")
                }

                override suspend fun updateData(
                    transform: suspend (t: Preferences) -> Preferences
                ): Preferences = error("unused")
            }
        val repo = SettingsRepository(failingStore)
        assertEquals(Settings(), repo.settings.first())
    }

    @Test
    fun `unrelated update preserves unknown future enum and swipe values on disk, while replace clears them`() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = settingsCorruptionHandler,
                    scope = backgroundScope,
                ) {
                    File(tempDir, "future.preferences_pb")
                }
            val iconShapeKey = stringPreferencesKey("icon_shape")
            val swipeRightKey = stringPreferencesKey("swipe_right")
            val futureSwipe = """{"type":"open_assistant","mode":"voice"}"""
            store.edit { prefs ->
                prefs[iconShapeKey] = "TEARDROP"
                prefs[swipeRightKey] = futureSwipe
            }

            val repo = SettingsRepository(store)
            assertEquals(IconShape.SYSTEM, repo.settings.first().iconShape)
            assertNull(repo.settings.first().swipeRight)

            repo.update { it.copy(homeLabels = true) }

            assertTrue(repo.settings.first().homeLabels)
            assertEquals("TEARDROP", store.data.first()[iconShapeKey])
            assertEquals(futureSwipe, store.data.first()[swipeRightKey])

            repo.replace(Settings(homeLabels = false))
            assertEquals(IconShape.SYSTEM.name, store.data.first()[iconShapeKey])
            assertNull(store.data.first()[swipeRightKey])
        }

    @Test
    fun `swipeActionOrNull ignores unknown extra fields and drops unknown action types`() {
        val withExtra =
            """{"type":"open_app","app":{"component":"pkg/.Main","profile":0},"extra":true}"""
        assertEquals(SwipeAction.OpenApp(AppRef("pkg/.Main", 0)), swipeActionOrNull(withExtra))
        assertNull(swipeActionOrNull("""{"type":"future_action"}"""))
    }

    @Test
    fun `wrong-typed preference keys on disk fall back to defaults instead of throwing ClassCastException`() =
        runTest {
            val store =
                PreferenceDataStoreFactory.create(
                    corruptionHandler = settingsCorruptionHandler,
                    scope = backgroundScope,
                ) {
                    File(tempDir, "mismatched.preferences_pb")
                }
            store.edit { prefs ->
                prefs[stringPreferencesKey("columns")] = "not-an-int"
                prefs[intPreferencesKey("icon_shape")] = 42
                prefs[intPreferencesKey("swipe_right")] = 99
            }
            val repo = SettingsRepository(store)
            assertEquals(Settings(), repo.settings.first())
        }
}
