package com.grayvines.runway

import android.content.Intent
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
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

        // Addressed explicitly: the test install resets the device's default home app.
        app.startActivity(
            Intent(Intent.ACTION_MAIN, null, app, LauncherActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
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
