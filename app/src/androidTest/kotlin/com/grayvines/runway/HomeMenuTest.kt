package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.menu.HOME_MENU_TAG
import com.grayvines.runway.ui.menu.ITEM_MENU_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The menu a long press on empty home space opens: wallpaper, a new page, settings. */
@RunWith(AndroidJUnit4::class)
class HomeMenuTest : LauncherFixture() {
    @Test
    fun aLongPressOnEmptySpaceOpensTheHomeMenuAndATapOutsideClosesIt() {
        holdEmptySpace()
        menuRow("Wallpaper").assertIsDisplayed()
        menuRow("Add page").assertIsDisplayed()
        menuRow("Settings").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(ITEM_MENU_TAG).fetchSemanticsNodes().isEmpty())
        compose.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 20f)) }
        waitUntil { compose.onAllNodesWithTag(HOME_MENU_TAG).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    fun aLongPressOnAnIconOpensItsMenuNotTheHomeMenu() {
        hold(icon(firstHomeApp))
        release()
        assertTrue(compose.onAllNodesWithTag(HOME_MENU_TAG).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun addPageAddsAHomePageAfterTheLastAndShowsIt() {
        val before = homePageCount()
        val neighbour = labelAtHomeCell(1, 0)
        holdEmptySpace()
        menuRow("Add page").performClick()
        waitUntil { homePageCount() == before + 1 }
        // Shown: the first page's icons have scrolled away.
        waitUntil { !icon(neighbour).isDisplayedOrFalse() }
        assertEquals(before + 1, homePageCount())
    }

    @Test
    fun settingsFromTheHomeMenuOpensSettings() {
        holdEmptySpace()
        menuRow("Settings").performClick()
        assertTrue(
            "settings did not open",
            device.wait(Until.hasObject(By.text("Grid")), TIMEOUT_MS),
        )
        device.pressBack()
    }

    @Test
    fun wallpaperFromTheHomeMenuHandsOffToTheSystemPicker() {
        holdEmptySpace()
        menuRow("Wallpaper").performClick()
        waitUntil(LONG_TIMEOUT_MS) { device.currentPackageName != app.packageName }
        sendHomeIntent() // back to the launcher, whatever the picker put up
        waitUntil(LONG_TIMEOUT_MS) { device.currentPackageName == app.packageName }
    }

    /**
     * A long press on empty space, finger lifted after. The first page is full by default, so the
     * first home app's placement goes first and its cell is where the press lands.
     */
    private fun holdEmptySpace() {
        val grid = useGrid(settings.columns, settings.rows)
        runBlocking { graph.workspace.removeItem(placementOf(firstHomeApp)!!.id) }
        waitUntil { !icon(firstHomeApp).isDisplayedOrFalse() }
        compose.onRoot().performTouchInput { down(grid.homeCell(0, 0)) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        waitUntil { compose.onAllNodesWithTag(HOME_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
        release()
    }

    private fun homePageCount() = runBlocking {
        graph.workspace.observe(Container.HOME).first().pages.size
    }
}
