package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DropGeometryTest {
    // Home: 300×200 px, 3×2 cells of 100×100. Dock below it: 300×50, 3 slots.
    private val areas =
        DropAreas(
            home = Bounds(0f, 0f, 300f, 200f),
            homePage = 1,
            columns = 3,
            rows = 2,
            dock = Bounds(0f, 200f, 300f, 250f),
            dockPage = 0,
            dockSlots = 3,
        )
    private val noGrab = Point(0f, 0f)

    @Test
    fun `pointer in a home cell snaps the item's corner to the nearest cell`() {
        assertEquals(DropTarget.HomeCell(1, 1, 0), areas.targetFor(Point(140f, 20f), noGrab, 1, 1))
        // Grabbed 40px into the item: corner is at 100, i.e. cell 1 exactly.
        assertEquals(
            DropTarget.HomeCell(1, 1, 1),
            areas.targetFor(Point(140f, 150f), Point(40f, 40f), 1, 1),
        )
    }

    @Test
    fun `the cell under the finger counts only well inside it`() {
        // Cell (1,0) spans x 100..200, y 0..100: the middle 60% is 120..180 each way.
        assertEquals(DropTarget.HomeCell(1, 1, 0), areas.cellUnder(Point(150f, 50f)))
        assertEquals(DropTarget.HomeCell(1, 1, 0), areas.cellUnder(Point(121f, 21f)))
        assertNull(areas.cellUnder(Point(110f, 50f))) // near the left edge: between icons
        assertNull(areas.cellUnder(Point(150f, 95f))) // near the bottom edge
        assertEquals(DropTarget.DockSlot(0, 2), areas.cellUnder(Point(250f, 225f)))
        assertNull(areas.cellUnder(Point(205f, 225f)))
        assertNull(areas.cellUnder(Point(150f, 300f))) // off both areas
        // A wider zone, for staying on an icon once folding: the same edge point now counts.
        assertEquals(DropTarget.HomeCell(1, 1, 0), areas.cellUnder(Point(110f, 50f), zone = 0.9f))
    }

    @Test
    fun `a span wider than the grid has no target rather than a crash`() {
        assertNull(areas.targetFor(Point(150f, 50f), noGrab, 4, 1))
        assertNull(areas.targetFor(Point(150f, 50f), noGrab, 1, 3))
    }

    @Test
    fun `spans are kept inside the grid`() {
        assertEquals(DropTarget.HomeCell(1, 1, 0), areas.targetFor(Point(290f, 10f), noGrab, 2, 2))
    }

    @Test
    fun `dock slots come from the pointer, not the corner`() {
        assertEquals(
            DropTarget.DockSlot(0, 2),
            areas.targetFor(Point(250f, 225f), Point(90f, 0f), 1, 1),
        )
    }

    @Test
    fun `edges are the outer quarter of the outer cell, on home and on the dock`() {
        val home = { edge: Edge -> EdgeHover(Container.HOME, edge) }
        val dock = { edge: Edge -> EdgeHover(Container.DOCK, edge) }
        assertEquals(home(Edge.LEFT), areas.edgeAt(Point(10f, 100f)))
        assertEquals(home(Edge.RIGHT), areas.edgeAt(Point(290f, 100f)))
        assertNull(areas.edgeAt(Point(150f, 100f)))
        assertNull(areas.edgeAt(Point(30f, 100f))) // inside the outer cell, past its outer quarter
        assertNull(areas.edgeAt(Point(250f, 100f))) // the outer cell's centre is a safe drop
        assertEquals(dock(Edge.RIGHT), areas.edgeAt(Point(280f, 225f)))
        assertNull(areas.edgeAt(Point(250f, 225f)))
        assertEquals(dock(Edge.LEFT), areas.edgeAt(Point(10f, 225f)))
        assertEquals(dock(Edge.RIGHT), areas.edgeAt(Point(340f, 225f)))
        assertNull(areas.edgeAt(Point(150f, 225f)))
        assertNull(areas.edgeAt(Point(10f, 300f))) // below everything
        assertEquals(
            home(Edge.RIGHT),
            areas.edgeAt(Point(340f, 100f)),
        ) // past the side still counts
        assertNull(DropAreas().edgeAt(Point(10f, 100f)))
    }

    @Test
    fun `past a side still drops into the edge column`() {
        assertEquals(DropTarget.HomeCell(1, 2, 0), areas.targetFor(Point(340f, 20f), noGrab, 1, 1))
        assertEquals(DropTarget.HomeCell(1, 0, 1), areas.targetFor(Point(-30f, 150f), noGrab, 1, 1))
        assertEquals(DropTarget.DockSlot(0, 2), areas.targetFor(Point(340f, 225f), noGrab, 1, 1))
    }

    @Test
    fun `outside every area is no target`() {
        assertNull(areas.targetFor(Point(150f, 300f), noGrab, 1, 1))
        assertNull(DropAreas().targetFor(Point(10f, 10f), noGrab, 1, 1))
    }
}
