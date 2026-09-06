package com.grayvines.runway

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
import com.grayvines.runway.ui.home.SEARCH_BAR_TAG
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings, the HOME intent, the search bar and icon sizing: the launcher around the drag. */
@RunWith(AndroidJUnit4::class)
class LauncherShellTest : LauncherFixture() {
    @Test
    fun tappingTheSettingsIconOpensSettings() {
        icon(firstHomeApp).performClick()
        assertTrue(
            "settings screen did not appear",
            device.wait(Until.hasObject(By.text("Grid")), TIMEOUT_MS),
        )
        device.pressBack()
    }

    @Test
    fun columnsSettingRelaysOutTheGridLive() {
        val before = icon(firstHomeApp).fetchSemanticsNode().size
        runBlocking { graph.settings.update { it.copy(columns = it.columns + 2) } }
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).fetchSemanticsNode().size != before }
    }

    @Test
    fun homeIntentReturnsToTheFirstPage() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeLeft() }
        compose.waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }

        sendHomeIntent()
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun tappingTheSearchBarHandsOffToTheTargetApp() {
        val target = graph.searchTargets.resolve(null)
        assumeTrue("no web-search handler installed", target != null)
        compose.onNodeWithTag(SEARCH_BAR_TAG).performClick()
        assertTrue(
            "${target!!.label} did not come to the front",
            device.wait(Until.hasObject(By.pkg(target.packageName)), TIMEOUT_MS),
        )
        sendHomeIntent()
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun theSearchBarFollowsThePickedTarget() {
        val handlers = graph.searchTargets.handlers()
        assumeTrue("no web-search handler installed", handlers.isNotEmpty())
        val picked = handlers.last()
        runBlocking { graph.settings.update { it.copy(searchTarget = picked.packageName) } }
        compose.waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(SEARCH_TARGET_ICON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .any { it.config[SemanticsProperties.ContentDescription] == listOf(picked.label) }
        }
    }

    @Test
    fun pickingASearchTargetInSettingsIsSaved() {
        val handlers = graph.searchTargets.handlers()
        assumeTrue("needs two web-search handlers to pick between", handlers.size >= 2)
        val current = graph.searchTargets.resolve(null)!!
        val other = handlers.first { it.packageName != current.packageName }
        icon(firstHomeApp).performClick()
        // Nothing picked yet, so the dropdown button reads Automatic.
        assertTrue(
            "settings did not open",
            device.wait(Until.hasObject(By.text("Automatic")), TIMEOUT_MS),
        )
        device.findObject(By.text("Automatic")).click()
        // The Compose rule owns the frame clock for every composition in the process, including
        // the settings screen: nothing there recomposes after a UiAutomator tap until it idles.
        compose.waitForIdle()
        assertTrue(
            "dropdown did not open",
            device.wait(Until.hasObject(By.text(other.label)), TIMEOUT_MS),
        )
        device.findObject(By.text(other.label)).click()
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { graph.settings.settings.first().searchTarget } == other.packageName
        }
        device.pressBack()
    }

    @Test
    fun searchBarShowsTheHandoffTarget() {
        val target = graph.searchTargets.resolve(null)
        // Bare CI images may have no browser at all; that is not a launcher bug.
        assumeTrue("no web-search handler installed", target != null)
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
}
