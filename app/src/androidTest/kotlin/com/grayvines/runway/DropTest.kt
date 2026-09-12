package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Where a drop lands: home cells, dock slots, displacement and reordering. */
@RunWith(AndroidJUnit4::class)
class DropTest : LauncherFixture() {
    @Test
    fun longPressDragMovesAnIconToAnEmptyCellAndBack() {
        // 5×5 page cells hold 25; the seed puts far fewer on page 1, so the last cell is free.
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.homeCell(4, 4))
        waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 4 to 4 }
        assertStillOnLauncher()

        // And back: the handler must see the item's new position, not its original one.
        drag(from = firstHomeApp, to = grid.homeCell(0, 0))
        waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 0 to 0 }
    }

    @Test
    fun aDragBegunWithASecondFingerFollowsThatFinger() {
        // One finger rests on an empty dock slot; another lifts an icon and carries it off. The
        // drag is that second finger's, and its release is the drop.
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        val resting = grid.dockSlot(settings.dockSlots)
        val start = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot.center
        val to = grid.homeCell(4, 4)
        compose.onRoot().performTouchInput {
            down(0, resting)
            down(1, start)
            advanceEventTime(LONG_PRESS_MS)
            var p = start
            repeat(DRAG_STEPS) {
                p += (to - start) / DRAG_STEPS.toFloat()
                moveTo(1, p)
                advanceEventTime(DRAG_STEP_MS)
            }
            up(1)
        }
        waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 4 to 4 }
        compose.onRoot().performTouchInput { up(0) }
        assertStillOnLauncher()
    }

    @Test
    fun theHomeIntentDuringADragPutsTheIconBackAndLeavesThePageAlone() {
        val grid = useGrid(columns = 5, rows = 7)
        holdDrag(from = firstHomeApp, to = grid.homeCell(4, 4))
        compose.onNodeWithTag(DRAG_OVERLAY_TAG).assertExists()
        sendHomeIntent()
        awaitGone(DRAG_OVERLAY_TAG)
        release() // the finger lifting afterwards drops nothing
        awaitDropSettled()
        assertEquals(0 to 0, homeCellOf(firstHomeApp))
        icon(firstHomeApp).assertIsDisplayed()
        assertStillOnLauncher()
    }

    @Test
    fun droppingBesideAnOccupiedCellsIconDisplacesItsOccupant() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        // Short of the icon's middle (which would fold with it) but far enough for the corner to
        // snap to that cell.
        drag(from = firstHomeApp, to = grid.homeCell(1, 0) - Offset(grid.cellWidth() * BESIDE, 0f))
        waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 1 to 0 }
        assertEquals(0 to 0, homeCellOf(neighbour)) // into the cell the mover vacated
    }

    @Test
    fun droppingIntoAnEmptyDockSlotMovesToTheDock() {
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        drag(from = firstHomeApp, to = grid.dockSlot(settings.dockSlots))
        waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.container == Container.DOCK }
        assertEquals(settings.dockSlots, placementOf(firstHomeApp)?.x)
    }

    @Test
    fun droppingBesideAnOccupiedDockSlotsIconSnapsBack() {
        val grid = useGrid(columns = 5, rows = 7)
        // Well clear of the icon: a finger that had crossed its middle would still be folding.
        drag(
            from = firstHomeApp,
            to = grid.dockSlot(0) + Offset(grid.dockSlotWidth() * WELL_BESIDE, 0f),
        )
        assertUnmoved(firstHomeApp)
        assertEquals(0, placementOf(firstDockApp)?.x)
    }

    @Test
    fun draggingADockIconOntoAnotherDockSlotReordersTheDock() {
        val grid = useGrid(columns = 5, rows = 7)
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        drag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots - 1))
        waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots - 1 }
        assertEquals(settings.dockSlots - 2, placementOf(lastDockApp)?.x) // shifted one left
        assertEquals(Container.DOCK, placementOf(firstDockApp)?.container)
    }

    @Test
    fun movingADockIconIntoAnEmptySlotLeavesTheOthersAlone() {
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        val neighbour = labelAtDockSlot(1)
        val last = labelAtDockSlot(settings.dockSlots - 1)
        drag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots))
        waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots }
        assertEquals(1, placementOf(neighbour)?.x)
        assertEquals(settings.dockSlots - 1, placementOf(last)?.x)
        assertEquals(1, grid.dockSlotAt(icon(neighbour).fetchSemanticsNode().boundsInRoot.center))
    }

    @Test
    fun aCellHeldByAnItemThatIsNotDrawnRefusesADrop() {
        // A placement from a profile that is off: nothing to draw, but the slot is taken.
        val hidden = AppRef("com.example.work/.Main", 99)
        runBlocking {
            val all = graph.appRepository.apps.first { it.isNotEmpty() }
            val ours = all.first { it.component.packageName == app.packageName }
            val others = (all - ours).map { it.ref }
            // As the seed lays it out, with the hidden placement in dock slot 1.
            val dock = listOf(others[0], hidden) + others.drop(1).take(settings.dockSlots - 2)
            graph.workspace.autoFill(
                dock + ours.ref + others.drop(settings.dockSlots - 1),
                settings.columns,
                settings.pageRows,
                settings.dockSlots,
            )
        }
        waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        drag(from = firstHomeApp, to = grid.dockSlot(1))
        assertUnmoved(firstHomeApp)
    }

    @Test
    fun dockIconsCanBeDraggedOntoAHomePage() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstDockApp, to = grid.homeCell(4, 4))
        waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.container == Container.HOME }
        val placed = placementOf(firstDockApp)!!
        assertEquals(Triple(0, 4, 4), Triple(placed.pageIndex, placed.x, placed.y))
    }
}
