package com.grayvines.runway.ui.widgets

import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WidgetResizeTest {
    private val grid = GridSize(5, 4)
    private val both =
        ResizeLimits(
            minWidth = 1,
            minHeight = 1,
            maxWidth = 4,
            maxHeight = 3,
            horizontal = true,
            vertical = true,
        )

    @Test
    fun `the limits cover the smallest size and fit inside the largest`() {
        val limits =
            resizeLimits(
                minWidthDp = 80f,
                minHeightDp = 40f,
                maxWidthDp = 180f,
                maxHeightDp = 110f,
                horizontal = true,
                vertical = false,
                cellWidthDp = 60f,
                cellHeightDp = 80f,
                grid = grid,
            )
        assertEquals(ResizeLimits(2, 1, 3, 1, horizontal = true, vertical = false), limits)
        assertEquals(listOf(Edge.LEFT, Edge.RIGHT), limits.edges)
    }

    @Test
    fun `no largest size means the grid is the limit, and one below the smallest yields to it`() {
        val limits =
            resizeLimits(
                minWidthDp = 150f,
                minHeightDp = 40f,
                maxWidthDp = 0f,
                maxHeightDp = 30f,
                horizontal = true,
                vertical = true,
                cellWidthDp = 60f,
                cellHeightDp = 80f,
                grid = grid,
            )
        assertEquals(ResizeLimits(3, 1, 5, 1, horizontal = true, vertical = true), limits)
    }

    @Test
    fun `each edge pulls its own way`() {
        val at = Footprint(2, 1, 1, 1)
        assertEquals(Footprint(1, 1, 2, 1), resized(at, Edge.LEFT, 1, both, grid, emptyList()))
        assertEquals(Footprint(2, 0, 1, 2), resized(at, Edge.TOP, 1, both, grid, emptyList()))
        assertEquals(Footprint(2, 1, 3, 1), resized(at, Edge.RIGHT, 2, both, grid, emptyList()))
        assertEquals(Footprint(2, 1, 1, 3), resized(at, Edge.BOTTOM, 2, both, grid, emptyList()))
    }

    @Test
    fun `a pull inward shrinks, but never below a cell or the smallest size`() {
        val at = Footprint(1, 1, 3, 2)
        assertEquals(Footprint(2, 1, 2, 2), resized(at, Edge.LEFT, -1, both, grid, emptyList()))
        assertEquals(Footprint(1, 1, 1, 2), resized(at, Edge.RIGHT, -5, both, grid, emptyList()))
        val wide = both.copy(minWidth = 2)
        assertEquals(Footprint(1, 1, 2, 2), resized(at, Edge.RIGHT, -5, wide, grid, emptyList()))
    }

    @Test
    fun `a pull stops at a neighbour, the grid's edge and the largest size`() {
        val at = Footprint(1, 1, 1, 1)
        val neighbour = listOf(Placed(9, Footprint(4, 1)))
        assertEquals(Footprint(1, 1, 3, 1), resized(at, Edge.RIGHT, 4, both, grid, neighbour))
        assertEquals(Footprint(0, 1, 2, 1), resized(at, Edge.LEFT, 3, both, grid, emptyList()))
        assertEquals(Footprint(1, 1, 1, 3), resized(at, Edge.BOTTOM, 5, both, grid, emptyList()))
    }

    @Test
    fun `a pull that cannot move at all changes nothing`() {
        val at = Footprint(0, 0, 2, 1)
        assertEquals(at, resized(at, Edge.LEFT, 2, both, grid, emptyList()))
        assertEquals(at, resized(at, Edge.TOP, 1, both, grid, emptyList()))
        val blocked = listOf(Placed(9, Footprint(2, 0)))
        assertEquals(at, resized(at, Edge.RIGHT, 1, both, grid, blocked))
    }

    @Test
    fun `an axis the provider fixes does not move`() {
        val at = Footprint(1, 1, 2, 1)
        val fixed = both.copy(vertical = false)
        assertEquals(at, resized(at, Edge.BOTTOM, 1, fixed, grid, emptyList()))
        assertEquals(Footprint(1, 1, 3, 1), resized(at, Edge.RIGHT, 1, fixed, grid, emptyList()))
    }

    @Test
    fun `a pull snaps just past half a cell, either way`() {
        assertEquals(0, cellsPulled(59f, 100f))
        assertEquals(1, cellsPulled(60f, 100f))
        assertEquals(1, cellsPulled(149f, 100f))
        assertEquals(2, cellsPulled(150f, 100f))
        assertEquals(0, cellsPulled(-59f, 100f))
        assertEquals(-1, cellsPulled(-60f, 100f))
        assertEquals(-2, cellsPulled(-170f, 100f))
    }

    @Test
    fun `leaving the cells it snapped to takes a little more than half a cell`() {
        assertEquals(1, cellsPulled(155f, 100f, from = 1))
        assertEquals(2, cellsPulled(161f, 100f, from = 1))
        assertEquals(1, cellsPulled(45f, 100f, from = 1))
        assertEquals(0, cellsPulled(39f, 100f, from = 1))
        // A jump straight past the band is not held back.
        assertEquals(3, cellsPulled(255f, 100f, from = 1))
    }
}
