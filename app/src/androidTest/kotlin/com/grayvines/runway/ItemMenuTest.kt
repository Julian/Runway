package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.menu.ITEM_MENU_TAG
import java.util.regex.Pattern
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The long-press item menu: showing it, its actions, and how a drag takes over from it. */
@RunWith(AndroidJUnit4::class)
class ItemMenuTest : LauncherFixture() {
    @Test
    fun aLongPressShowsTheMenuWithoutLiftingTheIcon() {
        longPress(firstHomeApp)
        compose.onNodeWithTag(ITEM_MENU_TAG).assertIsDisplayed()
        menuRow("Remove").assertIsDisplayed()
        menuRow("App info").assertIsDisplayed()
        menuRow("Uninstall").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithTag(DRAG_OVERLAY_TAG).fetchSemanticsNodes().isEmpty())
        release()
        compose.onNodeWithTag(ITEM_MENU_TAG).assertIsDisplayed() // stays up after the finger lifts
    }

    @Test
    fun movingWhileTheMenuIsUpDismissesItAndStartsTheDrag() {
        val grid = useGrid(columns = 5, rows = 7)
        longPress(firstHomeApp)
        dragOn(to = grid.homeCell(3, 3))
        awaitMenuGone()
        compose.onNodeWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true).assertIsDisplayed()
        release()
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 3 to 3 }
    }

    @Test
    fun tappingOutsideClosesTheMenu() {
        longPress(firstHomeApp)
        release()
        compose.onRoot().performTouchInput { click(Offset(centerX, centerY * 1.5f)) }
        awaitMenuGone()
        assertUnmoved(firstHomeApp)
    }

    @Test
    fun removeTakesTheIconOffThePage() {
        longPress(firstHomeApp)
        release()
        menuRow("Remove").performClick()
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp) == null }
        awaitMenuGone()
        compose.waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun removeWorksOnTheDockToo() {
        longPress(firstDockApp)
        release()
        menuRow("Remove").performClick()
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstDockApp) == null }
        assertNull(placementOf(firstDockApp))
    }

    @Test
    fun appInfoOpensTheSystemPageForTheApp() {
        longPress(firstDockApp)
        release()
        menuRow("App info").performClick()
        assertTrue(
            "app info did not open",
            device.wait(Until.hasObject(By.pkg(SETTINGS_PKG)), TIMEOUT_MS),
        )
        device.pressBack()
        compose.waitUntil(TIMEOUT_MS) { icon(firstDockApp).isDisplayedOrFalse() }
        awaitMenuGone()
    }

    @Test
    fun uninstallAsksTheSystemToConfirm() {
        // System apps cannot be uninstalled: the fixture app goes in the first cell instead.
        val removable = runBlocking {
            graph.appRepository.apps.first { it.isNotEmpty() }
        }
            .firstOrNull { it.component.packageName == FIXTURE_PACKAGE }
        assertNotNull(
            "the fixture app is not installed; Gradle installs it for the tests",
            removable,
        )
        removable!!
        runBlocking {
            graph.workspace.removeItem(placementOf(firstHomeApp)!!.id)
            graph.workspace.moveItem(
                placementOf(removable.label)!!.id,
                Container.HOME,
                0,
                0,
                0,
                emptyMap(),
            )
        }
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(removable.label) == 0 to 0 }
        longPress(removable.label)
        release()
        menuRow("Uninstall").performClick()
        assertTrue(
            "no uninstall dialog",
            device.wait(Until.hasObject(By.pkg(INSTALLER_PKG)), TIMEOUT_MS),
        )
        device.pressBack()
        compose.waitUntil(TIMEOUT_MS) { icon(removable.label).isDisplayedOrFalse() }
        assertEquals(0 to 0, homeCellOf(removable.label)) // declined: still there
    }

    private fun longPress(label: String) {
        val start = icon(label).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(ITEM_MENU_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitMenuGone() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(ITEM_MENU_TAG).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun menuRow(text: String) =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(ITEM_MENU_TAG)))

    private companion object {
        val SETTINGS_PKG: Pattern = Pattern.compile("com\\.android\\.settings")
        val INSTALLER_PKG: Pattern = Pattern.compile(".*packageinstaller.*")
    }
}
