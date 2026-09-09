package com.grayvines.runway

import androidx.compose.ui.test.assertIsDisplayed
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.settings.settingsStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** What the launcher makes of a settings store it did not write itself. */
@RunWith(AndroidJUnit4::class)
class SettingsStoreTest : LauncherFixture() {
    @Test
    fun aStoredGridWithNoRoomForItemsIsReadWithinBoundsAndTheHomeScreenStaysUp() {
        // Two rows: the dock and the search bar take both, leaving no row for a page. Written
        // behind the repository's back, as a corrupt or hand-edited file would be.
        runBlocking { app.settingsStore.edit { it[intPreferencesKey("rows")] = 2 } }
        waitUntil(TIMEOUT_MS) {
            runBlocking { graph.settings.settings.first().rows } == Settings.MIN_ROWS
        }
        waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
        icon(firstHomeApp).assertIsDisplayed()
        assertStillOnLauncher()
        assertEquals(Settings.MIN_ROWS, runBlocking { graph.settings.settings.first().rows })
    }
}
