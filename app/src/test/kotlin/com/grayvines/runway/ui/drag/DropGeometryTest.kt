package com.grayvines.runway.ui.drag

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
    fun `a visual zoom is undone before hit-testing`() {
        // Zoomed to half size about the centre (150,125): screen point (225,175) is unzoomed
        // (300,225),
        // i.e. the dock's right edge; (75,75) is unzoomed (0,25), the first home cell.
        val zoomed = areas.copy(zoom = 0.5f, zoomPivot = Point(150f, 125f))
        assertEquals(DropTarget.DockSlot(0, 2), zoomed.targetFor(Point(220f, 175f), noGrab, 1, 1))
        assertEquals(DropTarget.HomeCell(1, 0, 0), zoomed.targetFor(Point(75f, 75f), noGrab, 1, 1))
        // The same screen point without zoom lands elsewhere.
        assertEquals(DropTarget.HomeCell(1, 2, 1), areas.targetFor(Point(220f, 175f), noGrab, 1, 1))
    }

    @Test
    fun `outside every area is no target`() {
        assertNull(areas.targetFor(Point(150f, 300f), noGrab, 1, 1))
        assertNull(DropAreas().targetFor(Point(10f, 10f), noGrab, 1, 1))
    }
}
