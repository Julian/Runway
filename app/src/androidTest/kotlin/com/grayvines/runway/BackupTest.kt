package com.grayvines.runway

import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.clear
import com.grayvines.runway.data.settings.Settings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A backup file brings a cleared launcher back: the layout and the settings. */
@RunWith(AndroidJUnit4::class)
class BackupTest : LauncherFixture() {
    @Test
    fun restoringABackupBringsTheLayoutAndTheSettingsBack() {
        val before = placementsOf(firstHomeApp).single()
        val allBefore = labels.sumOf { placementsOf(it).size }
        val columns = settings.columns + 1
        runBlocking { graph.settings.update { it.copy(columns = columns) } }
        compose.waitUntil(TIMEOUT_MS) { currentColumns() == columns }
        val backup = runBlocking { graph.backup.export() }

        runBlocking {
            graph.workspace.clear()
            graph.settings.update { Settings() }
        }
        compose.waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }

        val installed = runBlocking {
            graph.appRepository.apps.first()
        }
            .mapTo(mutableSetOf()) { it.ref }
        val restored = runBlocking { graph.backup.restore(backup, installed) }

        assertEquals(0, restored.skipped)
        assertEquals(allBefore, restored.placed)
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
        val after = placementsOf(firstHomeApp).single()
        assertEquals(
            Triple(before.container, before.pageIndex, before.x to before.y),
            Triple(after.container, after.pageIndex, after.x to after.y),
        )
        assertEquals(columns, currentColumns())
    }

    @Test
    fun settingsOfferToSaveAndRestoreABackup() {
        icon(firstHomeApp).performClick()
        assertTrue(
            "settings did not open",
            device.wait(Until.hasObject(By.text("Grid")), TIMEOUT_MS),
        )
        // The backup section is near the bottom: on a short screen, below the fold. The Compose
        // rule owns the frame clock, so the page only moves once the test idles.
        val save = By.text("Save a backup")
        if (!device.hasObject(save)) {
            device.findObject(By.scrollable(true))?.visibleBounds?.let { page ->
                device.swipe(
                    page.centerX(),
                    page.bottom - SWIPE_INSET_PX,
                    page.centerX(),
                    page.top + SWIPE_INSET_PX,
                    SWIPE_STEPS,
                )
            }
            compose.waitForIdle()
        }
        assertTrue("no backup buttons", device.wait(Until.hasObject(save), TIMEOUT_MS))
        assertTrue(device.hasObject(By.text("Restore a backup")))
        device.pressBack()
    }

    private fun currentColumns() = runBlocking { graph.settings.settings.first().columns }

    private companion object {
        const val SWIPE_INSET_PX = 100
        const val SWIPE_STEPS = 20
    }
}
