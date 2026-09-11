package com.grayvines.runway

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.DragMotion
import com.grayvines.runway.ui.home.SEARCH_BAR_EDGE_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import com.grayvines.runway.ui.widgets.WIDGET_RESIZE_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A long press lifts a widget; it follows the finger at its own size and lands where its cells fit,
 * pushing single icons aside; where they do not fit it goes back where it was.
 */
@RunWith(AndroidJUnit4::class)
class WidgetDragTest : LauncherFixture() {
    @Test
    fun aLiftedWidgetIsCarriedAtItsOwnSize() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        holdDragAt(widgetCentre(), grid.homeCell(2, 2))
        waitUntil { compose.onAllNodesWithTag(DRAG_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty() }
        val carried = compose.onNodeWithTag(DRAG_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot
        val expected = grid.cellWidth() * 2
        assertTrue(
            "carried at ${carried.width} wide, not about $expected",
            abs(carried.width - expected) < grid.cellWidth() * 0.15f,
        )
        release()
    }

    @Test
    fun aWidgetCarriedOntoTheSearchBarOutlinesTheBar() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        compose.onAllNodesWithTag(SEARCH_BAR_EDGE_TAG).assertCountEquals(0)
        holdDragAt(widgetCentre(), grid.searchBar())
        waitUntil {
            compose.onAllNodesWithTag(SEARCH_BAR_EDGE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // The picture stops at the pages' top while the finger is over the bar (its lifted scale
        // grows it about its centre, so its drawn top sits a little above the box it is held in).
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        val carried = compose.onNodeWithTag(DRAG_OVERLAY_TAG).fetchSemanticsNode().boundsInRoot
        val grown = carried.height * (DragMotion.WIDGET_LIFTED_SCALE - 1f) / 2
        assertTrue(
            "carried at $carried, over the bar; pages at $pages",
            carried.top >= pages.top - grown - 1f,
        )
        dragOn(grid.homeCell(2, 2))
        waitUntil { compose.onAllNodesWithTag(SEARCH_BAR_EDGE_TAG).fetchSemanticsNodes().isEmpty() }
        release()
    }

    /**
     * The drag session is remade after every drop; the second lift must still carry a picture (not
     * the blank slab) and the settle after a drop must still draw one.
     */
    @Test
    fun aWidgetLiftedAgainAfterADropIsStillCarriedAsItself() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        repeat(2) {
            holdDragAt(widgetCentre(), grid.homeCell(2, 2))
            waitUntil {
                compose.onAllNodesWithTag(DRAG_OVERLAY_TAG).fetchSemanticsNodes().isNotEmpty()
            }
            // The picture is an image with a description; the slab has none.
            val described = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription)
            compose
                .onNode(hasTestTag(DRAG_OVERLAY_TAG) and described)
                .assertExists("lift ${it + 1} carried the blank slab, not the widget's picture")
            release()
            awaitGone(DRAG_OVERLAY_TAG)
        }
    }

    @Test
    fun droppingAWidgetOnFreeCellsMovesItThere() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        clearHomeCells(0 to 2, 1 to 2) // the seed's pages are three rows deep
        awaitWidgetCell()
        // The grab is the widget's centre: the footprint's corner lands a cell up-left of it.
        val target = (grid.homeCell(0, 2) + grid.homeCell(1, 2)) / 2f
        holdDragAt(widgetCentre(), target)
        release()
        awaitWidgetAt(0 to 2)
        assertEquals(2 to 1, widget().spanX to widget().spanY)
        awaitGone(DRAG_OVERLAY_TAG)
        val shown = compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.center
        assertTrue("drawn at $shown, not at $target", (shown - target).getDistance() < 2f)
        // Settled in its new cells, it is framed for resizing, as a newly placed widget is.
        waitUntil {
            compose.onAllNodesWithTag(WIDGET_RESIZE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun droppingAWidgetOntoIconsPushesThemAside() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        clearHomeCells(2 to 2, 3 to 2) // somewhere for the pushed icons to go
        val pushed = listOf(labelAtHomeCell(0, 1), labelAtHomeCell(1, 1))
        awaitWidgetCell()
        val target = (grid.homeCell(0, 1) + grid.homeCell(1, 1)) / 2f
        holdDragAt(widgetCentre(), target)
        release()
        awaitWidgetAt(0 to 1)
        pushed.forEach { label ->
            waitUntil { homeCellOf(label).let { it != null && it != 0 to 1 && it != 1 to 1 } }
        }
        assertEquals(emptyList<ItemEntity>(), folders())
    }

    @Test
    fun aWidgetDroppedOnTheDockGoesBack() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        holdDragAt(widgetCentre(), grid.dockSlot(1))
        release()
        awaitGone(DRAG_OVERLAY_TAG)
        assertEquals(0 to 0, widget().let { it.x to it.y })
        assertEquals(Container.HOME, widget().container)
    }

    /** On a full page the only room is what the widget itself leaves, and that is enough. */
    @Test
    fun onAFullPageTheIconsAWidgetLandsOnTakeTheCellsItLeft() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        val pushed = listOf(labelAtHomeCell(0, 2), labelAtHomeCell(1, 2))
        awaitWidgetCell()
        holdDragAt(widgetCentre(), (grid.homeCell(0, 2) + grid.homeCell(1, 2)) / 2f)
        release()
        awaitWidgetAt(0 to 2)
        waitUntil { pushed.map { homeCellOf(it) }.toSet() == setOf(0 to 0, 1 to 0) }
    }

    @Test
    fun aWidgetDroppedOnAnotherWidgetGoesBack() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val first = placeFixtureWidget(0, 0)
        placeFixtureWidget(0, 2)
        waitUntil { compose.onAllNodesWithTag(WIDGET_TAG).fetchSemanticsNodes().size == 2 }
        val from = (grid.homeCell(0, 0) + grid.homeCell(1, 0)) / 2f
        holdDragAt(from, (grid.homeCell(0, 2) + grid.homeCell(1, 2)) / 2f)
        release()
        awaitGone(DRAG_OVERLAY_TAG)
        val moved = runBlocking { graph.workspace.dao.item(first) }!!
        assertEquals(0 to 0, moved.x to moved.y)
    }

    @Test
    fun holdingAWidgetAtTheRightEdgeFlipsThePageAndDropsThere() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        val onPageTwo = labelOnPage(1)
        holdDragAt(widgetCentre(), grid.rightEdge(row = settings.pageRows - 1))
        waitUntil(TIMEOUT_MS) { icon(onPageTwo).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        waitUntil(TIMEOUT_MS) { widget().pageIndex == 1 }
    }

    /** Waits for the widget's placement to reach [cell]; the failure says where it is instead. */
    private fun awaitWidgetAt(cell: Pair<Int, Int>) {
        try {
            waitUntil { widget().let { it.x to it.y } == cell }
        } catch (e: ComposeTimeoutException) {
            val w = widget()
            throw AssertionError("widget at (${w.x}, ${w.y}) on page ${w.pageIndex}, not $cell", e)
        }
    }

    private fun widgetCentre() =
        compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.center

    private fun widget(): ItemEntity = runBlocking {
        listOf(Container.HOME)
            .flatMap { graph.workspace.observe(it).first().pages }
            .flatMap { it.items }
            .single { it.kind == ItemKind.WIDGET }
    }

    private fun folders(): List<ItemEntity> = runBlocking {
        graph.workspace
            .observe(Container.HOME)
            .first()
            .pages
            .flatMap { it.items }
            .filter {
                it.kind == ItemKind.FOLDER
            }
    }
}
