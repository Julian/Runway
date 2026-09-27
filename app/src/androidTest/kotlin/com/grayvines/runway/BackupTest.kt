package com.grayvines.runway

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.clear
import com.grayvines.runway.data.hideApp
import com.grayvines.runway.data.observeHiddenApps
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.unhideApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A backup file brings a cleared launcher back: the layout, the settings and the hidden apps. */
@RunWith(AndroidJUnit4::class)
class BackupTest : LauncherFixture() {
    @Test
    fun restoringABackupBringsTheLayoutTheSettingsAndTheHiddenAppsBack() {
        val before = placementsOf(firstHomeApp).single()
        val allBefore = labels.sumOf { placementsOf(it).size }
        val columns = settings.columns + 1
        val hidden = apps.first { it.label == firstDockApp }.ref
        runBlocking {
            graph.settings.update { it.copy(columns = columns) }
            graph.workspace.hideApp(hidden)
        }
        waitUntil(TIMEOUT_MS) { currentColumns() == columns }
        val backup = runBlocking { graph.backup.export() }

        runBlocking {
            graph.workspace.clear()
            graph.settings.update { Settings() }
            graph.workspace.unhideApp(hidden)
        }
        waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }

        val installed = runBlocking {
            graph.appRepository.apps.first()
        }
            .mapTo(mutableSetOf()) { it.ref }
        val restored = runBlocking { graph.backup.restore(backup, installed) }

        assertEquals(0, restored.skipped)
        assertEquals(allBefore, restored.placed)
        waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
        val after = placementsOf(firstHomeApp).single()
        assertEquals(
            Triple(before.container, before.pageIndex, before.x to before.y),
            Triple(after.container, after.pageIndex, after.x to after.y),
        )
        assertEquals(columns, currentColumns())
        assertEquals(setOf(hidden), runBlocking { graph.workspace.observeHiddenApps().first() })
        waitUntil(TIMEOUT_MS) {
            compose.activity.viewModel.state.value.drawerApps.none { it.ref == hidden }
        }
    }

    @Test
    fun settingsOfferToSaveAndRestoreABackup() {
        icon(firstHomeApp).performClick()
        awaitSettingsOpen()
        openSettingsPage("Backup")
        assertTrue(
            "no backup buttons",
            device.wait(Until.hasObject(By.text("Save a backup")), TIMEOUT_MS),
        )
        assertTrue(device.hasObject(By.text("Restore a backup")))
        leaveSettings()
    }

    private fun currentColumns() = runBlocking { graph.settings.settings.first().columns }
}
