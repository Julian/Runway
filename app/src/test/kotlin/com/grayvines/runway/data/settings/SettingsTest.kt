package com.grayvines.runway.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.intPreferencesKey
import com.grayvines.runway.data.AppRef
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
}
