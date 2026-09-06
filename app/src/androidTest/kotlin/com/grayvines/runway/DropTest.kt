package com.grayvines.runway

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.Container
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
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 4 to 4 }
        assertStillOnLauncher()

        // And back: the handler must see the item's new position, not its original one.
        drag(from = firstHomeApp, to = grid.homeCell(0, 0))
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 0 to 0 }
    }

    @Test
    fun droppingOnAnOccupiedCellDisplacesItsOccupant() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 1 to 0 }
        assertEquals(0 to 0, homeCellOf(neighbour)) // into the cell the mover vacated
    }

    @Test
    fun droppingIntoAnEmptyDockSlotMovesToTheDock() {
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        drag(from = firstHomeApp, to = grid.dockSlot(settings.dockSlots))
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.container == Container.DOCK }
        assertEquals(settings.dockSlots, placementOf(firstHomeApp)?.x)
    }

    @Test
    fun droppingOnAnOccupiedDockSlotSnapsBack() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.dockSlot(0))
        assertUnmoved(firstHomeApp)
        assertEquals(0, placementOf(firstDockApp)?.x)
    }

    @Test
    fun draggingADockIconOntoAnotherDockSlotReordersTheDock() {
        val grid = useGrid(columns = 5, rows = 7)
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        drag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots - 1))
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots - 1 }
        assertEquals(settings.dockSlots - 2, placementOf(lastDockApp)?.x) // shifted one left
        assertEquals(Container.DOCK, placementOf(firstDockApp)?.container)
    }

    @Test
    fun movingADockIconIntoAnEmptySlotLeavesTheOthersAlone() {
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        val neighbour = labelAtDockSlot(1)
        val last = labelAtDockSlot(settings.dockSlots - 1)
        drag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots))
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots }
        assertEquals(1, placementOf(neighbour)?.x)
        assertEquals(settings.dockSlots - 1, placementOf(last)?.x)
        assertEquals(1, grid.dockSlotAt(icon(neighbour).fetchSemanticsNode().boundsInRoot.center))
    }

    @Test
    fun dockIconsCanBeDraggedOntoAHomePage() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstDockApp, to = grid.homeCell(4, 4))
        compose.waitUntil(TIMEOUT_MS) {
            placementOf(firstDockApp)?.let { it.container == Container.HOME && it.x == 4 } ?: false
        }
    }
}
