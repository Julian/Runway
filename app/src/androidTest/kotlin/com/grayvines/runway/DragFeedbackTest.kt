package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.DragMotion
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** How a drag looks: the press, the lift, the pull-back, and the settle after release. */
@RunWith(AndroidJUnit4::class)
class DragFeedbackTest : LauncherFixture() {
    @Test
    fun aTouchedIconShrinksUntilReleased() {
        val resting = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { down(resting.center) }
        // Shorter than the long-press timeout, so this is a touch and not a lift.
        compose.mainClock.advanceTimeBy(PRESS_SETTLE_MS)
        val pressed = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "pressed ${pressed.width} vs resting ${resting.width}",
            pressed.width < resting.width,
        )
        // Cancel rather than lift: lifting would complete a tap and launch the app.
        compose.onRoot().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        assertEquals(resting.width, icon(firstHomeApp).fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test
    fun theHomeAreaZoomsOutWhileDragging() {
        val grid = useGrid(columns = 5, rows = 7)
        val resting = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        holdDrag(from = firstHomeApp, to = grid.homeCell(4, 4))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val zoomed = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "zoomed ${zoomed.width} vs resting ${resting.width}",
            zoomed.width < resting.width,
        )
        release()
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val back = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(resting.width, back.width, 1f)
    }

    @Test
    fun theLiftedIconIsDrawnLargerThanItsCellIcon() {
        val grid = useGrid(columns = 5, rows = 7)
        val resting = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot
        holdDrag(from = firstHomeApp, to = grid.homeCell(4, 4))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val lifted =
            compose
                .onNodeWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "lifted ${lifted.width} vs resting ${resting.width}",
            lifted.width > resting.width * 1.1f,
        )
        release()
    }

    @Test
    fun aLiftedDockIconRidesWhereItWasGrabbedWhenSlotsAreWiderThanCells() {
        // Three dock slots across five columns: a slot is five thirds of a cell wide. Grabbed by
        // its middle, the icon must stay centred on the finger, not sit a third of a cell off it.
        val grid = useGrid(columns = 5, rows = 7)
        val start = icon(firstDockApp).fetchSemanticsNode().boundsInRoot.center
        val to = grid.homeCell(2, 3)
        holdDragAt(start, to)
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val lifted =
            compose
                .onNodeWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
                .center
        assertEquals("off the finger sideways", to.x, lifted.x, GRAB_TOLERANCE_PX)
        assertEquals("off the finger vertically", to.y, lifted.y, GRAB_TOLERANCE_PX)
        release()
    }

    @Test
    fun theLiftAndThePullBackMoveAsOne() {
        useGrid(columns = 5, rows = 7)
        val restingIcon = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot.width
        val restingArea =
            compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot.width
        val start = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performTouchInput { down(start) }
            // Run the hold (the menu appears), then a nudge starts the drag; watch frame by frame
            // from the moment the icon lifts.
            compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
            compose.onRoot().performTouchInput { moveBy(Offset(0f, -LIFT_NUDGE_PX)) }
            val icons = mutableListOf<Float>()
            val areas = mutableListOf<Float>()
            repeat(LIFT_FRAMES) {
                compose.mainClock.advanceTimeByFrame()
                val overlay =
                    compose
                        .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .firstOrNull()
                if (overlay != null) {
                    icons += overlay.boundsInRoot.width
                    areas +=
                        compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot.width
                }
            }
            assertTrue("the icon never lifted", icons.size > LIFT_FRAMES / 2)
            assertTrue(
                "lift starts at the pressed size, not a jump to full: ${icons.first()} vs $restingIcon",
                icons.first() < restingIcon,
            )
            val iconPeak = icons.indexOf(icons.max())
            val areaTrough = areas.indexOf(areas.min())
            assertTrue(
                "icon peaked at frame $iconPeak but the area bottomed out at $areaTrough",
                abs(iconPeak - areaTrough) <= 1,
            )
            assertTrue(
                "no overshoot on lift: ${icons.max()} vs ${restingIcon * DragMotion.LIFTED_SCALE}",
                icons.max() > restingIcon * DragMotion.LIFTED_SCALE,
            )
            assertEquals(restingIcon * DragMotion.LIFTED_SCALE, icons.last(), 2f)
            assertEquals(restingArea * DragMotion.ZOOM, areas.last(), 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        release()
    }

    @Test
    fun aDroppedIconIsDrawnAtItsTargetBeforeTheDatabaseCatchesUp() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        // Released off-centre (and clear of the icon's middle, which would fold with it); the
        // cell must start there and only ever approach its slot.
        holdDrag(
            from = firstHomeApp,
            to = grid.homeCell(1, 0) - Offset(grid.cellWidth() * BESIDE, 0f),
        )
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        release()
        assertSettlesTowards(firstHomeApp, grid.homeCell(1, 0))
        assertEquals(grid.cellAt(icon(neighbour).fetchSemanticsNode().boundsInRoot.center), 0 to 0)
    }

    @Test
    fun aReorderedDockIsDrawnSettledOneFrameAfterRelease() {
        val grid = useGrid(columns = 5, rows = 7)
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        holdDrag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots - 1))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        // Once the neighbour has slid over for the hover (a rest on the real clock)...
        waitUntil(TIMEOUT_MS) {
            val at = icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center
            grid.dockSlotAt(at) == settings.dockSlots - 2
        }
        // ...hold the clock, or the frame after the release would be the finished state, and
        // check the neighbour stays put rather than springing back for a frame.
        compose.mainClock.autoAdvance = false
        release()
        compose.mainClock.advanceTimeByFrame()
        val shown = icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = true
        assertEquals(settings.dockSlots - 2, grid.dockSlotAt(shown))
        assertSettlesTowards(firstDockApp, grid.dockSlot(settings.dockSlots - 1))
        // And it stays there while the database catches up.
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots - 1 }
        assertEquals(
            settings.dockSlots - 2,
            grid.dockSlotAt(icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center),
        )
    }

    @Test
    fun anAdjacentDockReorderSettlesWithoutRevisitingItsOldSlot() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtDockSlot(1)
        // Released off-centre, as a finger does: the settle has real distance to cover.
        holdDrag(from = firstDockApp, to = grid.dockSlot(1) + Offset(grid.dockSlotWidth() / 3, 0f))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        release()
        assertSettlesTowards(firstDockApp, grid.dockSlot(1))
        assertEquals(0, grid.dockSlotAt(icon(neighbour).fetchSemanticsNode().boundsInRoot.center))
    }

    @Test
    fun droppingOutsideAnyAreaSlidesBackToItsCell() {
        val grid = useGrid(columns = 5, rows = 7)
        holdDrag(from = firstHomeApp, to = grid.searchBar())
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        release()
        assertSettlesTowards(firstHomeApp, grid.homeCell(0, 0))
        assertUnmoved(firstHomeApp)
    }

    @Test
    fun dockNeighboursSlideOverWhileTheDragIsStillInProgress() {
        val grid = useGrid(columns = 5, rows = 7)
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        holdDrag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots - 1))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        // Neighbours hold still until the finger has rested on the slot a moment (the
        // coordinator's unit test times that; here the rest runs on the real clock, so a slow
        // device may already be past it), then slide over while the finger is still down.
        waitUntil(TIMEOUT_MS) {
            val shown = icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center
            grid.dockSlotAt(shown) == settings.dockSlots - 2
        }
        release()
    }

    private companion object {
        /** The overlay is placed to the pixel; the finger's steps land within one too. */
        const val GRAB_TOLERANCE_PX = 4f
    }
}
