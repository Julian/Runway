package com.grayvines.runway

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.hideApp
import com.grayvines.runway.data.observeHiddenApps
import com.grayvines.runway.data.unhideApp
import com.grayvines.runway.system.apps.LabelOrder
import com.grayvines.runway.ui.drawer.DRAWER_SEARCH_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Hidden apps: chosen in settings, left out of the drawer, and nowhere else. */
@RunWith(AndroidJUnit4::class)
class HiddenAppsTest : LauncherFixture() {
    @Test
    fun aHiddenAppIsLeftOutOfTheDrawerUntilUnhidden() {
        val all = runBlocking {
            graph.appRepository.apps
                .first { it.isNotEmpty() }
                .map { it.label }
                .sortedWith(LabelOrder.comparator())
        }
        // The first app the fixture knows by label: near enough the top that the rows in view
        // would show it.
        val hidden = labels.first()
        hide(hidden)
        openDrawer()
        waitUntil { drawerTopIs(all - hidden) }

        runBlocking { graph.workspace.unhideApp(apps.first { it.label == hidden }.ref) }
        waitUntil { drawerTopIs(all) }
    }

    /** Whether the drawer's rows in view, and those composed past them, begin [labels]. */
    private fun drawerTopIs(labels: List<String>) =
        shownDrawerLabels().let { it.isNotEmpty() && it == labels.take(it.size) }

    @Test
    fun searchingLeavesOutAHiddenApp_andEnterLaunchesNothing() {
        hide(firstHomeApp)
        cellIcon(firstHomeApp).assertIsDisplayed() // hidden from the drawer, not from the pages
        openDrawer()
        // The search that finds this app alone when it is not hidden.
        compose.onNodeWithTag(DRAWER_SEARCH_TAG).performTextInput(firstHomeApp)
        waitUntil { drawerItems().isEmpty() }

        compose.onNodeWithTag(DRAWER_SEARCH_TAG).performImeAction()
        Thread.sleep(WRITE_GRACE_MS) // a launch would have taken the screen by now
        assertStillOnLauncher()
        compose.onNodeWithTag(DRAWER_TAG).assertIsDisplayed()
    }

    @Test
    fun anAppIsHiddenFromSettings_andShownAgainFromThere() {
        // Near the top of the settings list, as it is of the drawer's.
        val app = apps.first()
        icon(firstHomeApp).performClick()
        awaitSettingsOpen()
        openSettingsPage("Drawer")
        device.findObject(By.text("Hidden apps")).click()
        compose.waitForIdle() // the rule's frame clock drives the settings screen too
        val row = By.text(app.label)
        assertTrue("${app.label} is not listed", device.wait(Until.hasObject(row), TIMEOUT_MS))

        device.findObject(row).click()
        compose.waitForIdle()
        waitUntil(TIMEOUT_MS) { app.ref in hiddenApps() }
        device.findObject(row).click()
        compose.waitForIdle()
        waitUntil(TIMEOUT_MS) { app.ref !in hiddenApps() }
        leaveSettings()
    }

    private fun hiddenApps() = runBlocking { graph.workspace.observeHiddenApps().first() }

    private fun hide(label: String) = runBlocking {
        graph.workspace.hideApp(apps.first { it.label == label }.ref)
    }
}
