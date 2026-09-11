package com.grayvines.runway

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.ui.home.SEARCH_BAR_EDGE_TAG
import com.grayvines.runway.ui.menu.ITEM_MENU_TAG
import com.grayvines.runway.ui.widgets.REFLECT_TIMEOUT_MS
import com.grayvines.runway.ui.widgets.WIDGET_RESIZE_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A long press on a widget frames it: handles on the edges its provider lets move, and Remove. A
 * handle pulled grows or shrinks the widget a cell at a time, as far as the grid, its neighbours
 * and its provider's sizes allow. A touch anywhere else puts the frame away.
 */
@RunWith(AndroidJUnit4::class)
class WidgetResizeTest : LauncherFixture() {
    private val grid
        get() = Grid(settings.columns, settings.pageRows, settings.dockSlots)

    @Test
    fun holdingAWidgetFramesItWithHandlesAndRemove_andNoMenu() {
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        holdWidget()
        release()
        compose.onAllNodesWithTag(ITEM_MENU_TAG).assertCountEquals(0)
        listOf("Left edge", "Top edge", "Right edge", "Bottom edge", "Remove").forEach {
            compose.onNode(hasContentDescription(it) and inFrame()).assertIsDisplayed()
        }
    }

    @Test
    fun pullingTheRightHandleGrowsTheWidgetACell_andTheFrameFollows() {
        placeFixtureWidget(0, 0)
        clearHomeCells(2 to 0)
        awaitWidgetCell()
        holdWidget()
        release()
        pull("Right edge", Offset(grid.cellWidth(), 0f))
        awaitWidgetSize(3 to 1)
        assertEquals(0 to 0, widget().let { it.x to it.y })
        frame().assertIsDisplayed()
        // The frame is around the grown widget, not the cells it had: its handle is on the new
        // edge.
        waitUntil {
            val edge = compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.right
            val handle = handle("Right edge").boundsInRoot.center.x
            abs(handle - edge) < 2f
        }
    }

    @Test
    fun aHandleHeldStillKeepsItsPull() {
        // The pulled size used to be given up after a while with no change, meant for a save that
        // never showed; a finger resting on the handle looked the same, and the widget snapped
        // back under it.
        placeFixtureWidget(0, 0)
        clearHomeCells(2 to 0)
        awaitWidgetCell()
        holdWidget()
        release()
        val start = handle("Right edge").boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            var p = start
            repeat(DRAG_STEPS) {
                p += Offset(grid.cellWidth() / DRAG_STEPS, 0f)
                moveTo(p)
                advanceEventTime(DRAG_STEP_MS)
            }
        }
        val pulled = compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.width
        assertTrue("the pull should show three cells", abs(pulled - 3 * grid.cellWidth()) < 2f)
        compose.mainClock.advanceTimeBy(REFLECT_TIMEOUT_MS + FRAME_MS)
        val held = compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.width
        assertTrue("held still, the widget went back to $held px", abs(held - pulled) < 2f)
        compose.onRoot().performTouchInput { up() }
        awaitWidgetSize(3 to 1)
    }

    @Test
    fun pullingTheLeftHandleMovesTheWidgetsCornerWithIt() {
        placeFixtureWidget(2, 0)
        clearHomeCells(1 to 0)
        awaitWidgetCell()
        holdWidget()
        release()
        pull("Left edge", Offset(-grid.cellWidth(), 0f))
        awaitWidgetSize(3 to 1)
        assertEquals(1 to 0, widget().let { it.x to it.y })
    }

    @Test
    fun pullingTheBottomHandleGrowsTheWidgetDown() {
        placeFixtureWidget(0, 0)
        clearHomeCells(0 to 1, 1 to 1)
        awaitWidgetCell()
        holdWidget()
        release()
        pull("Bottom edge", Offset(0f, grid.cellHeight()))
        awaitWidgetSize(2 to 2)
    }

    @Test
    fun aPulledHandleStopsAtANeighbour() {
        placeFixtureWidget(0, 0) // (2, 0) stays the seed's icon
        awaitWidgetCell()
        holdWidget()
        release()
        pull("Right edge", Offset(grid.cellWidth() * 2, 0f))
        // The cell would already be drawn at the pulled size were the pull taken.
        val shown = compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("drawn ${shown.width} wide", abs(shown.width - grid.cellWidth() * 2) < 2f)
        assertEquals(2 to 1, widget().let { it.spanX to it.spanY })
    }

    @Test
    fun aPulledHandleStopsAtTheProvidersLargestSize() {
        placeFixtureWidget(0, 0)
        clearHomeCells(2 to 0, 3 to 0)
        awaitWidgetCell()
        val density = app.resources.displayMetrics.density
        val most = floor(FIXTURE_WIDGET_MAX_DP / (grid.cellWidth() / density)).toInt()
        assertTrue("the grid's cells give no room to show the limit", most in 2 until 4)
        holdWidget()
        release()
        pull("Right edge", Offset(grid.cellWidth() * 3, 0f))
        awaitWidgetSize(most to 1)
    }

    @Test
    fun aHandlePulledIntoTheSearchBarOutlinesTheBar() {
        placeFixtureWidget(0, 0) // the top row: the bar is just above
        awaitWidgetCell()
        holdWidget()
        release()
        val start = handle("Top edge").boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            var p = start
            repeat(DRAG_STEPS) {
                p += Offset(0f, -grid.cellHeight() / DRAG_STEPS)
                moveTo(p)
                advanceEventTime(DRAG_STEP_MS)
            }
        }
        waitUntil {
            compose.onAllNodesWithTag(SEARCH_BAR_EDGE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onRoot().performTouchInput { up() }
        waitUntil { compose.onAllNodesWithTag(SEARCH_BAR_EDGE_TAG).fetchSemanticsNodes().isEmpty() }
        assertEquals(0 to 0, widget().let { it.x to it.y }) // nothing to grow into
    }

    @Test
    fun aTouchElsewherePutsTheFrameAway() {
        placeFixtureWidget(0, 0)
        clearHomeCells(3 to 2)
        awaitWidgetCell()
        holdWidget()
        release()
        compose.onRoot().performTouchInput { click(grid.homeCell(3, 2)) }
        awaitGone(WIDGET_RESIZE_TAG)
        compose.onNodeWithTag(WIDGET_TAG).assertIsDisplayed()
        assertStillOnLauncher()
    }

    @Test
    fun aTapOnTheFramedWidgetPutsTheFrameAwayAndReachesNothing() {
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        holdWidget()
        release()
        compose.onRoot().performTouchInput { click(widgetCentre()) }
        awaitGone(WIDGET_RESIZE_TAG)
        // Whatever the widget would do with a tap (a default view opens its app) must not happen:
        // the launcher's window keeps the screen. Its own views carry the provider's package, so
        // the window is what to look at, not any node of that package.
        SystemClock.sleep(APP_OPEN_MS)
        assertEquals("the tap reached the widget", app.packageName, device.currentPackageName)
        compose.onNodeWithTag(WIDGET_TAG).assertIsDisplayed()
        assertStillOnLauncher()
    }

    private fun widgetCentre() =
        compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.center

    @Test
    fun backPutsTheFrameAway() {
        placeFixtureWidget(0, 0)
        awaitWidgetCell()
        holdWidget()
        release()
        device.pressBack()
        awaitGone(WIDGET_RESIZE_TAG)
        assertStillOnLauncher()
    }

    @Test
    fun removeOnTheFrameDropsThePlacementAndTheHostId() {
        val itemId = placeFixtureWidget(0, 0)
        awaitWidgetCell()
        val id = widget().appWidgetId!!
        holdWidget()
        release()
        compose.onNode(hasContentDescription("Remove") and inFrame()).performClick()
        awaitGone(WIDGET_TAG)
        waitUntil(TIMEOUT_MS) { graph.widgets.info(id) == null }
        assertNull(widgets().firstOrNull { it.id == itemId })
    }

    /** A long press on the widget, finger left down; the frame is up when this returns. */
    private fun holdWidget() {
        val centre = compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(centre) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        waitUntil {
            compose.onAllNodesWithTag(WIDGET_RESIZE_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** Drags the handle described as [handle] by [by] (root px) in steps, and lets go. */
    private fun pull(handle: String, by: Offset) {
        val start = handle(handle).boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            var p = start
            repeat(DRAG_STEPS) {
                p += by / DRAG_STEPS.toFloat()
                moveTo(p)
                advanceEventTime(DRAG_STEP_MS)
            }
            up()
        }
    }

    private fun frame() = compose.onNodeWithTag(WIDGET_RESIZE_TAG)

    private fun handle(label: String) =
        compose.onNode(hasContentDescription(label) and inFrame()).fetchSemanticsNode()

    private fun inFrame() = hasAnyAncestor(hasTestTag(WIDGET_RESIZE_TAG))

    private fun awaitWidgetSize(span: Pair<Int, Int>) {
        waitUntil { widget().let { it.spanX to it.spanY } == span }
    }

    private fun widgets(): List<ItemEntity> = runBlocking {
        graph.workspace
            .observe(Container.HOME)
            .first()
            .pages
            .flatMap { it.items }
            .filter {
                it.kind == ItemKind.WIDGET
            }
    }

    private fun widget(): ItemEntity = widgets().single()

    private companion object {
        /** The fixture widget's maxResizeWidth. */
        const val FIXTURE_WIDGET_MAX_DP = 400f

        /** Long enough for a tapped widget's app to come up, when one does. */
        const val APP_OPEN_MS = 1_500L
    }
}
