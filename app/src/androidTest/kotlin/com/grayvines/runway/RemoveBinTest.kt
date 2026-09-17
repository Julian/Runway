package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.FolderContent
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.ui.home.DOCK_TAG
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.REMOVE_BIN_LIT_TAG
import com.grayvines.runway.ui.home.REMOVE_BIN_TAG
import com.grayvines.runway.ui.home.SEARCH_BAR_EDGE_TAG
import com.grayvines.runway.ui.home.SEARCH_BAR_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import com.grayvines.runway.ui.widgets.WIDGET_RESIZE_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bin between the dock and the pages: it comes up with a placement lifted off a page or the
 * dock, lights up while the finger is on it, and takes what is let go there.
 */
@RunWith(AndroidJUnit4::class)
class RemoveBinTest : LauncherFixture() {
    @Test
    fun anIconLetGoOnTheBinIsRemoved() {
        compose.onAllNodesWithTag(REMOVE_BIN_TAG).assertCountEquals(0) // nothing at rest
        lift(icon(firstHomeApp))
        compose.onAllNodesWithTag(REMOVE_BIN_LIT_TAG).assertCountEquals(0) // not lit till it is on
        val bin = bin()
        assertBetweenPagesAndDock(bin)
        dragOn(to = bin)
        awaitLit()
        release()
        waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp) == null }
        awaitGone(REMOVE_BIN_TAG)
        awaitDropSettled()
        assertTrue("still drawn", !icon(firstHomeApp).isDisplayedOrFalse())
        assertStillOnLauncher()
    }

    @Test
    fun aDockIconLetGoOnTheBinIsRemoved() {
        val neighbour = labelAtDockSlot(1)
        val before = placementOf(neighbour)
        lift(icon(firstDockApp))
        dragOn(to = bin())
        awaitLit()
        release()
        waitUntil(TIMEOUT_MS) { placementOf(firstDockApp) == null }
        assertEquals(before, placementOf(neighbour)) // the rest of the dock stays put
    }

    @Test
    fun aFolderLetGoOnTheBinGoesWithItsApps() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        waitUntil(TIMEOUT_MS) { folderAt(1, 0) != null }
        awaitDropSettled()
        lift(compose.onNodeWithContentDescription("Folder", useUnmergedTree = true))
        dragOn(to = bin())
        awaitLit()
        release()
        waitUntil(TIMEOUT_MS) { folderAt(1, 0) == null }
        assertEquals(
            emptyList<FolderContent>(),
            runBlocking { graph.workspace.observeFolders().first() },
        )
    }

    @Test
    fun aWidgetLetGoOnTheBinIsRemovedAndItsIdGivenBack() {
        // With the bar below the pages the bin sits over it, and a widget carried there is going,
        // not pressing against the bar.
        runBlocking { graph.settings.update { it.copy(searchBarAtTop = false) } }
        waitUntil(TIMEOUT_MS) { bounds(SEARCH_BAR_TAG).top >= bounds(WORKSPACE_TAG).bottom }
        val placed = placeFixtureWidget(0, 0)
        val widgetId = runBlocking { graph.workspace.dao.item(placed) }!!.appWidgetId!!
        awaitWidgetCell()
        liftAt(bounds(WIDGET_TAG).center)
        val bin = bin()
        assertBetweenPagesAndDock(bin)
        dragOn(to = bin)
        awaitLit()
        compose.onAllNodesWithTag(SEARCH_BAR_EDGE_TAG).assertCountEquals(0)
        release()
        waitUntil(TIMEOUT_MS) { runBlocking { graph.workspace.dao.item(placed) } == null }
        awaitGone(WIDGET_TAG)
        // The host's id is given back.
        waitUntil(TIMEOUT_MS) { graph.widgets.info(widgetId) == null }
        // Gone, it has nowhere to be framed.
        compose.onAllNodesWithTag(WIDGET_RESIZE_TAG).assertCountEquals(0)
        assertNull(compose.activity.viewModel.widgetResize.shown.value)
    }

    @Test
    fun anIconCarriedOverTheBinAndOnLandsWhereItIsLetGo() {
        val grid = useGrid(columns = 5, rows = 7)
        lift(icon(firstHomeApp))
        dragOn(to = bin())
        awaitLit()
        dragOn(to = grid.homeCell(4, 4))
        awaitGone(REMOVE_BIN_LIT_TAG)
        release()
        waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 4 to 4 }
    }

    @Test
    fun backWhileOnTheBinPutsTheIconBack() {
        lift(icon(firstHomeApp))
        dragOn(to = bin())
        awaitLit()
        pressBack()
        awaitGone(DRAG_OVERLAY_TAG)
        release() // the finger lifting afterwards removes nothing
        assertUnmoved(firstHomeApp)
        awaitGone(REMOVE_BIN_TAG)
    }

    @Test
    fun anAppOutOfTheDrawerHasNoBin() {
        val label = labelOnPage(1)
        val before = placementsOf(label)
        // Where the bin would be: the middle of the dock's top edge.
        val spot = bounds(DOCK_TAG).let { Offset(it.center.x, it.top) }
        val bar = bounds(SEARCH_BAR_TAG).center
        openDrawer()
        lift(drawerApp(label))
        awaitDrawerClosed()
        dragOn(to = spot)
        compose.waitForIdle()
        compose.onAllNodesWithTag(REMOVE_BIN_TAG).assertCountEquals(0)
        dragOn(to = bar) // over the search bar: nowhere to drop
        release()
        awaitDropSettled()
        assertEquals(before, placementsOf(label))
    }

    /** The bin's middle is between the pages' bottom and the dock's top, the bar if any between. */
    private fun assertBetweenPagesAndDock(bin: Offset) {
        val pages = bounds(WORKSPACE_TAG)
        val dock = bounds(DOCK_TAG)
        assertTrue(
            "the bin at $bin is not between the pages at $pages and the dock at $dock",
            bin.y in pages.bottom - PIXEL..dock.top + PIXEL,
        )
    }

    private fun bounds(tag: String) = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    /** The bin's middle (root px), once it has come up with the lift. */
    private fun bin(): Offset {
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(REMOVE_BIN_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        return compose.onNodeWithTag(REMOVE_BIN_TAG).fetchSemanticsNode().boundsInRoot.center
    }

    private fun awaitLit() =
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(REMOVE_BIN_LIT_TAG).fetchSemanticsNodes().isNotEmpty()
        }

    private companion object {
        /** Positions are rounded to pixels on their way through layout. */
        const val PIXEL = 1f
    }
}
