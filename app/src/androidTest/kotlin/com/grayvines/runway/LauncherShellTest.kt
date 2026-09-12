package com.grayvines.runway

import android.os.SystemClock
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.SEARCH_BAR_TAG
import com.grayvines.runway.ui.home.SEARCH_MENU_TAG
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import com.grayvines.runway.ui.menu.HOME_MENU_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings, the HOME intent, the search bar and icon sizing: the launcher around the drag. */
@RunWith(AndroidJUnit4::class)
class LauncherShellTest : LauncherFixture() {
    @Test
    fun tappingTheSettingsIconOpensSettings() {
        icon(firstHomeApp).performClick()
        awaitSettingsOpen()
        device.pressBack()
    }

    @Test
    fun columnsSettingRelaysOutTheGridLive() {
        val before = icon(firstHomeApp).fetchSemanticsNode().size
        runBlocking { graph.settings.update { it.copy(columns = it.columns + 2) } }
        waitUntil(TIMEOUT_MS) { icon(firstHomeApp).fetchSemanticsNode().size != before }
    }

    @Test
    fun homeIntentReturnsToTheFirstPage() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeLeft() }
        waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }

        sendHomeIntent()
        waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun backOnTheBareHomeScreenLeavesTheLauncherUp() {
        val activity = compose.activity
        device.pressBack()
        // A finished home activity is started again by the system at once, so a fresh screen would
        // look fine: what must not have happened is the finish itself.
        SystemClock.sleep(BACK_GRACE_MS)
        assertFalse("back finished the launcher", activity.isFinishing || activity.isDestroyed)
        icon(firstHomeApp).assertIsDisplayed()
    }

    @Test
    fun backDuringADragPutsTheIconBack() {
        val grid = useGrid(columns = 5, rows = 7)
        holdDrag(from = firstHomeApp, to = grid.homeCell(4, 4))
        compose.onNodeWithTag(DRAG_OVERLAY_TAG).assertExists()
        device.pressBack()
        awaitGone(DRAG_OVERLAY_TAG)
        release() // the finger lifting afterwards drops nothing
        assertUnmoved(firstHomeApp)
        assertStillOnLauncher()
    }

    @Test
    fun tappingTheSearchBarHandsOffToTheTargetApp() {
        val target = graph.searchTargets.resolve(null)
        assertNotNull(NO_HANDLER, target)
        compose.onNodeWithTag(SEARCH_BAR_TAG).performClick()
        assertTrue(
            "${target!!.label} did not come to the front",
            device.wait(Until.hasObject(By.pkg(target.packageName)), TIMEOUT_MS),
        )
        sendHomeIntent()
        waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun theSearchBarFollowsThePickedTarget() {
        // A handler other than the automatic choice, or the setting changes nothing.
        val automatic = graph.searchTargets.resolve(null)?.packageName
        val picked = graph.searchTargets.handlers().firstOrNull { it.packageName != automatic }
        assertNotNull(ONE_HANDLER, picked)
        picked!!
        runBlocking { graph.settings.update { it.copy(searchTarget = picked.packageName) } }
        waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(SEARCH_TARGET_ICON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.ContentDescription] == listOf(picked.label) }
        }
    }

    @Test
    fun pickingASearchTargetInSettingsIsSaved() {
        val handlers = graph.searchTargets.handlers()
        val current = graph.searchTargets.resolve(null)
        assertNotNull(NO_HANDLER, current)
        val other = handlers.firstOrNull { it.packageName != current!!.packageName }
        assertNotNull(ONE_HANDLER, other)
        other!!
        icon(firstHomeApp).performClick()
        awaitSettingsOpen()
        // Nothing picked yet, so the dropdown button reads Automatic.
        val automatic = By.text("Automatic")
        scrollSettingsTo(automatic)
        assertTrue(
            "no Automatic search target in settings",
            device.wait(Until.hasObject(automatic), TIMEOUT_MS),
        )
        device.findObject(automatic).click()
        // The Compose rule owns the frame clock for every composition in the process, including
        // the settings screen: nothing there recomposes after a UiAutomator tap until it idles.
        compose.waitForIdle()
        assertTrue(
            "dropdown did not open",
            device.wait(Until.hasObject(By.text(other.label)), TIMEOUT_MS),
        )
        device.findObject(By.text(other.label)).click()
        compose.waitForIdle()
        waitUntil(TIMEOUT_MS) {
            runBlocking { graph.settings.settings.first().searchTarget } == other.packageName
        }
        device.pressBack()
    }

    @Test
    fun theSearchBarsThreeDotsOpenTheHomeMenu_andSettingsFromThereOpensSettings() {
        compose.onNodeWithTag(SEARCH_MENU_TAG).performClick()
        waitUntil { compose.onAllNodesWithTag(HOME_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
        menuRow("Settings").performClick()
        awaitSettingsOpen()
        device.pressBack()
    }

    @Test
    fun searchBarShowsTheHandoffTarget() {
        val target = graph.searchTargets.resolve(null)
        assertNotNull(NO_HANDLER, target)
        compose
            .onNodeWithTag(SEARCH_TARGET_ICON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(target!!.label)
    }

    @Test
    fun iconsShareOneSizeAcrossGridAndDock() {
        val grid = icon(firstHomeApp).fetchSemanticsNode().size
        val dock = icon(firstDockApp).fetchSemanticsNode().size
        assertEquals(grid, dock)
        assertTrue(grid.width > 0)
    }

    private companion object {
        /** Long enough for a finish, had Back caused one, to have gone through. */
        const val BACK_GRACE_MS = 1_000L
        const val NO_HANDLER =
            "no web-search handler: the fixture app is one, and Gradle installs it for the tests"
        const val ONE_HANDLER =
            "only one web-search handler: picking needs a browser on the image beside the fixture app"
    }
}
