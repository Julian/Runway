package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.ui.folder.FOLDER_ITEM_TAG
import com.grayvines.runway.ui.folder.FOLDER_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Folders: made by dropping one icon onto another, and added to the same way. */
@RunWith(AndroidJUnit4::class)
class FolderTest : LauncherFixture() {
    @Test
    fun droppingAnIconOntoAnotherMakesAFolderOfTheTwo() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        assertEquals(listOf(neighbour, firstHomeApp), folderAt(1, 0))
        assertNull("the dropped icon's own placement is gone", placementOf(firstHomeApp))
        compose.onNodeWithContentDescription("Folder", useUnmergedTree = true).assertIsDisplayed()
        assertStillOnLauncher()
    }

    @Test
    fun droppingAnIconOntoAFolderAddsItToTheFolder() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        val third = labelAtHomeCell(2, 0)
        drag(from = third, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0)?.size == 3 }
        assertEquals(third, folderAt(1, 0)?.last())
        assertNull(placementOf(third))
    }

    @Test
    fun droppingAHomeIconOntoADockIconMakesAFolderInTheDock() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.dockSlot(0))
        compose.waitUntil(TIMEOUT_MS) { dockFolderAt(0) != null }
        assertEquals(listOf(firstDockApp, firstHomeApp), dockFolderAt(0))
        assertNull(placementOf(firstHomeApp))
    }

    @Test
    fun tappingAFolderOpensItShowingItsAppsByName() {
        val neighbour = makeFolder()
        compose.onNodeWithTag(FOLDER_TAG).assertIsDisplayed()
        folderApp(firstHomeApp).assertIsDisplayed()
        folderApp(neighbour).assertIsDisplayed()
    }

    @Test
    fun tappingAnAppInAnOpenFolderLaunchesItAndClosesTheFolder() {
        makeFolder()
        folderApp(firstHomeApp).performClick()
        assertTrue(
            "settings did not open",
            device.wait(Until.hasObject(By.text("Grid")), TIMEOUT_MS),
        )
        device.pressBack()
        awaitFolderClosed()
    }

    @Test
    fun tappingOutsideAnOpenFolderClosesIt() {
        makeFolder()
        compose.onRoot().performTouchInput { click(bottomCenter - Offset(0f, 20f)) }
        awaitFolderClosed()
    }

    @Test
    fun backAndTheHomeIntentCloseAnOpenFolder() {
        makeFolder()
        device.pressBack()
        awaitFolderClosed()
        compose.onNodeWithContentDescription("Folder", useUnmergedTree = true).performClick()
        compose.onNodeWithTag(FOLDER_TAG).assertIsDisplayed()
        sendHomeIntent()
        awaitFolderClosed()
        assertStillOnLauncher()
    }

    @Test
    fun theFolderGrowsOutOfItsTileAndShrinksBackIntoIt() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        val tile = grid.homeCell(1, 0)
        // Finger down with the clock running, up with it held: the tap lands on the up, and from
        // then on frames are stepped by hand so the motion can be watched.
        compose.onRoot().performTouchInput { down(tile) }
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performTouchInput { up() }
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            val early = compose.onNodeWithTag(FOLDER_TAG).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "should start out at its tile $tile, not ${early.center}",
                (early.center - tile).getDistance() < grid.cellWidth() * 2,
            )
            compose.mainClock.advanceTimeBy(SETTLE_MS)
            val rest = compose.onNodeWithTag(FOLDER_TAG).fetchSemanticsNode().boundsInRoot
            val screen = compose.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue(
                "should come to rest mid-screen, not at ${rest.center}",
                (rest.center - screen.center).getDistance() < grid.cellWidth(),
            )
            assertTrue(
                "should have grown: ${early.width} -> ${rest.width}",
                rest.width > early.width,
            )

            device.pressBack()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            compose.onNodeWithTag(FOLDER_TAG).assertExists() // still on its way back
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitFolderClosed()
    }

    /** Folds the first home app into its neighbour and opens the folder; returns the neighbour. */
    private fun makeFolder(): String {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        compose.onNodeWithContentDescription("Folder", useUnmergedTree = true).performClick()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        return neighbour
    }

    private fun folderApp(label: String) =
        compose.onNode(hasTestTag(FOLDER_ITEM_TAG) and hasContentDescription(label))

    private fun awaitFolderClosed() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithTag(FOLDER_TAG).assertCountEquals(0)
    }

    private companion object {
        const val SETTLE_MS = 1_000L
    }
}
