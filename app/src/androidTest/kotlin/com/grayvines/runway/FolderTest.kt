package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.FolderContent
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.ui.folder.FOLDER_ITEM_TAG
import com.grayvines.runway.ui.folder.FOLDER_NAME_TAG
import com.grayvines.runway.ui.folder.FOLDER_TAG
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.FOLD_HINT_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
        waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        assertEquals(listOf(neighbour, firstHomeApp), folderAt(1, 0))
        assertNull("the dropped icon's own placement is gone", placementOf(firstHomeApp))
        compose.onNodeWithContentDescription("Folder", useUnmergedTree = true).assertIsDisplayed()
        assertStillOnLauncher()
    }

    @Test
    fun hoveringOverAnIconShowsItTurningIntoAFolder() {
        val grid = useGrid(columns = 5, rows = 7)
        holdDrag(from = firstHomeApp, to = grid.homeCell(1, 0))
        waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(FOLD_HINT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // And the lifted icon has shrunk, so the tile and the icon it would join stay in view.
        waitUntil(TIMEOUT_MS) { overlayWidth() < grid.cellWidth() * SHRUNK }
        // Off to the side, over an empty cell: the hint goes and the icon is lifted large again.
        dragOn(grid.homeCell(3, 3))
        waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(FOLD_HINT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        waitUntil(TIMEOUT_MS) { overlayWidth() > grid.cellWidth() * SHRUNK }
        release()
    }

    @Test
    fun droppingAnIconOntoAFolderAddsItToTheFolder() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        val third = labelAtHomeCell(2, 0)
        drag(from = third, to = grid.homeCell(1, 0))
        waitUntil(TIMEOUT_MS) { folderAt(1, 0)?.size == 3 }
        assertEquals(third, folderAt(1, 0)?.last())
        assertNull(placementOf(third))
    }

    @Test
    fun droppingAHomeIconOntoADockIconMakesAFolderInTheDock() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.dockSlot(0))
        waitUntil(TIMEOUT_MS) { dockFolderAt(0) != null }
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
    fun tappingTheNameSelectsAllOfItSoTypingReplacesIt() {
        makeFolder()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performClick()
        compose.onNodeWithTag(FOLDER_NAME_TAG).assertIsFocused()
        val selection =
            compose
                .onNodeWithTag(FOLDER_NAME_TAG)
                .fetchSemanticsNode()
                .config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(0, "Folder".length), selection)
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextInput("Games") // no clearing first
        compose.onNodeWithTag(FOLDER_NAME_TAG).performImeAction()
        waitUntil(TIMEOUT_MS) { folderName() == "Games" }
    }

    @Test
    fun aNameTypedAndThenTappedAwayFromIsKept() {
        makeFolder()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performClick()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextClearance()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextInput("Games")
        // Straight out of the folder, without Done: what was typed is what the folder is called.
        // A tap up top, where the keyboard is not.
        compose.onRoot().performTouchInput { click(topCenter + Offset(0f, ABOVE_SHEET_PX)) }
        awaitFolderClosed()
        waitUntil(TIMEOUT_MS) { folderName() == "Games" }
        compose.onNodeWithContentDescription("Games", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun aNameTypedAndThenLeftByHomeIsKept() {
        makeFolder()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performClick()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextClearance()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextInput("Work")
        sendHomeIntent()
        awaitFolderClosed()
        waitUntil(TIMEOUT_MS) { folderName() == "Work" }
    }

    @Test
    fun aNameClearedAndThenTappedAwayFromKeepsTheOldOne() {
        makeFolder()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performClick()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextClearance()
        compose.onRoot().performTouchInput { click(topCenter + Offset(0f, ABOVE_SHEET_PX)) }
        awaitFolderClosed()
        Thread.sleep(WRITE_GRACE_MS)
        assertEquals("Folder", folderName())
    }

    @Test
    fun tappingTheNameOfAnOpenFolderLetsYouRenameIt() {
        makeFolder()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performClick()
        compose.onNodeWithTag(FOLDER_NAME_TAG).assertIsFocused()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextClearance()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextInput("Tools")
        compose.onNodeWithTag(FOLDER_NAME_TAG).performImeAction()
        waitUntil(TIMEOUT_MS) { folderName() == "Tools" }
        compose.onNodeWithTag(FOLDER_NAME_TAG).assertTextEquals("Tools")
        // Up top, clear of a keyboard that may still be on its way down after Done.
        compose.onRoot().performTouchInput { click(topCenter + Offset(0f, ABOVE_SHEET_PX)) }
        awaitFolderClosed()
        compose.onNodeWithContentDescription("Tools", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun doneOnABlankNamePutsTheOldOneBackAndKeepsTheFieldOpen() {
        makeFolder()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performClick()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performTextClearance()
        compose.onNodeWithTag(FOLDER_NAME_TAG).performImeAction()
        compose.onNodeWithTag(FOLDER_NAME_TAG).assertIsFocused() // still editing
        compose.onNodeWithTag(FOLDER_NAME_TAG).assertTextEquals("Folder")
        assertEquals("Folder", folderName())
    }

    @Test
    fun draggingAnAppOutOfAnOpenFolderPutsItInTheCellItIsDroppedOn() {
        val neighbour = makeFolder()
        val grid = useGrid(columns = 5, rows = 7)
        liftFromFolder(firstHomeApp)
        dragOn(to = grid.homeCell(0, 0)) // its old cell, empty since it folded away
        awaitFolderClosed() // the sheet went as soon as the app lifted
        release()
        waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 0 to 0 }
        assertEquals(listOf(neighbour), folderAt(1, 0))
    }

    @Test
    fun draggingTheLastAppOutOfAFolderDissolvesIt() {
        val neighbour = makeFolder()
        val grid = useGrid(columns = 5, rows = 7)
        liftFromFolder(firstHomeApp)
        dragOn(to = grid.homeCell(0, 0))
        release()
        waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 0 to 0 }

        tap(compose.onNodeWithContentDescription("Folder", useUnmergedTree = true))
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        val (x, y) = freeHomeCell(columns = 5, pageRows = 5)
        liftFromFolder(neighbour)
        dragOn(to = grid.homeCell(x, y))
        release()
        waitUntil(TIMEOUT_MS) { homeCellOf(neighbour) == x to y }
        assertNull("the emptied folder is gone", folderAt(1, 0))
        assertEquals(
            emptyList<FolderContent>(),
            runBlocking { graph.workspace.observeFolders().first() },
        )
    }

    @Test
    fun droppingAnAppBackOntoItsOwnFolderLeavesItThere() {
        val neighbour = makeFolder()
        val grid = useGrid(columns = 5, rows = 7)
        liftFromFolder(firstHomeApp)
        dragOn(to = grid.homeCell(1, 0))
        release()
        awaitGone(DRAG_OVERLAY_TAG)
        assertEquals(listOf(neighbour, firstHomeApp), folderAt(1, 0))
        assertNull(placementOf(firstHomeApp))
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
    fun anOpenFolderWhosePlacementGoesClosesItself() {
        makeFolder()
        val placement = runBlocking {
            graph.workspace.observe(Container.HOME).first().pages.first().items.first {
                it.kind == ItemKind.FOLDER
            }
        }
        runBlocking { graph.workspace.removeItem(placement.id) } // as Remove in its menu would
        awaitFolderClosed()
        assertEquals(
            emptyList<FolderContent>(),
            runBlocking { graph.workspace.observeFolders().first() },
        )
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
        waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
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

    private fun overlayWidth() =
        compose
            .onNodeWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .width

    /** Folds the first home app into its neighbour and opens the folder; returns the neighbour. */
    private fun makeFolder(): String {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        tap(compose.onNodeWithContentDescription("Folder", useUnmergedTree = true))
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        return neighbour
    }

    /** Long-presses [label] in the open folder and nudges it, so the drag has begun. */
    private fun liftFromFolder(label: String) {
        val start = folderApp(label).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        compose.onRoot().performTouchInput { moveBy(Offset(0f, -LIFT_NUDGE_PX)) }
        waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun folderName() = runBlocking {
        graph.workspace.observeFolders().first().singleOrNull()?.name
    }

    private fun folderApp(label: String) =
        compose.onNode(hasTestTag(FOLDER_ITEM_TAG) and hasContentDescription(label))

    private fun awaitFolderClosed() {
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(FOLDER_TAG).fetchSemanticsNodes().isEmpty()
        }
        compose.onAllNodesWithTag(FOLDER_TAG).assertCountEquals(0)
    }

    private companion object {
        const val SETTLE_MS = 1_000L

        /** Below the status bar, above where the sheet sits over a keyboard. */
        const val ABOVE_SHEET_PX = 200f

        /** Of a cell's width: below it the lifted icon is shrunk for a fold, above it lifted. */
        const val SHRUNK = 0.7f
    }
}
