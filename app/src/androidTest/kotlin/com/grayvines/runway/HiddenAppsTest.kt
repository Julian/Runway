package com.grayvines.runway

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.hideApp
import com.grayvines.runway.data.unhideApp
import com.grayvines.runway.system.apps.LabelOrder
import com.grayvines.runway.ui.drawer.DRAWER_SEARCH_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/** Hidden apps: left out of the drawer, and nowhere else. */
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

    private fun hide(label: String) = runBlocking {
        graph.workspace.hideApp(apps.first { it.label == label }.ref)
    }
}
