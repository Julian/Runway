package com.grayvines.runway

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.settings.settingsCorruptionHandler
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** A settings file that is not a settings file. */
@RunWith(AndroidJUnit4::class)
class SettingsCorruptionTest {
    @Test
    fun anUnreadableSettingsFileReadsAsEmptyAndTakesNewWrites() {
        // The launcher's own store is open for the life of the process, so the same handler is
        // put over a file of its own, filled with what a settings file never holds.
        val dir = ApplicationProvider.getApplicationContext<RunwayApp>().cacheDir
        val file = File(dir, "corrupt.preferences_pb").apply { writeText("not a settings file") }
        val store =
            PreferenceDataStoreFactory.create(corruptionHandler = settingsCorruptionHandler) {
                file
            }
        runBlocking {
            assertEquals(emptyMap<Any, Any>(), store.data.first().asMap())
            store.edit { it[intPreferencesKey("rows")] = 8 }
            assertEquals(8, store.data.first()[intPreferencesKey("rows")])
        }
        file.delete()
    }
}
