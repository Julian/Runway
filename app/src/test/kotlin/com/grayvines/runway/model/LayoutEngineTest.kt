package com.grayvines.runway.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class LayoutEngineTest {
    private val grid = GridSize(columns = 4, rows = 3)

    private fun at(id: Long, x: Int, y: Int, w: Int = 1, h: Int = 1) =
        Placed(id, Footprint(x, y, w, h))

    @Nested
    inner class CanPlace {
        @Test
        fun `empty grid accepts anything in bounds`() {
            assertTrue(LayoutEngine.canPlace(grid, emptyList(), Footprint(0, 0)))
            assertTrue(LayoutEngine.canPlace(grid, emptyList(), Footprint(3, 2)))
            assertTrue(LayoutEngine.canPlace(grid, emptyList(), Footprint(0, 0, 4, 3)))
        }

        @Test
        fun `rejects out of bounds`() {
            assertFalse(LayoutEngine.canPlace(grid, emptyList(), Footprint(4, 0)))
            assertFalse(LayoutEngine.canPlace(grid, emptyList(), Footprint(-1, 0)))
            assertFalse(LayoutEngine.canPlace(grid, emptyList(), Footprint(3, 0, 2, 1)))
            assertFalse(LayoutEngine.canPlace(grid, emptyList(), Footprint(0, 2, 1, 2)))
        }

        @Test
        fun `rejects an overlap`() {
            val items = listOf(at(1, 1, 1, 2, 2))
            assertFalse(LayoutEngine.canPlace(grid, items, Footprint(2, 2)))
            assertTrue(LayoutEngine.canPlace(grid, items, Footprint(0, 0)))
        }
    }

    @Nested
    inner class FindFreeCell {
        @Test
        fun `scans row by row`() {
            val items = listOf(at(1, 0, 0), at(2, 1, 0))
            assertEquals(Cell(2, 0), LayoutEngine.findFreeCell(grid, items))
        }

        @Test
        fun `honours span`() {
            val items = listOf(at(1, 0, 0), at(2, 2, 0))
            assertEquals(Cell(0, 1), LayoutEngine.findFreeCell(grid, items, width = 2, height = 1))
            assertEquals(Cell(0, 1), LayoutEngine.findFreeCell(grid, items, width = 4, height = 2))
        }

        @Test
        fun `null when full`() {
            val full = (0 until 12).map { at(it.toLong(), it % 4, it / 4) }
            assertNull(LayoutEngine.findFreeCell(grid, full))
            assertNull(LayoutEngine.findFreeCell(grid, emptyList(), width = 5))
        }
    }

    @Nested
    inner class DisplaceFor {
        @Test
        fun `no moves needed on a free target`() {
            val items = listOf(at(1, 0, 0), at(2, 3, 2))
            assertEquals(
                emptyMap<Long, Footprint>(),
                LayoutEngine.displaceFor(grid, items, 1, Footprint(1, 1)),
            )
        }

        @Test
        fun `single-cell blocker moves to the cell the mover vacated beside it`() {
            val items = listOf(at(1, 0, 0), at(2, 1, 0))
            val moves =
                LayoutEngine.displaceFor(grid, items, movingId = 1, target = Footprint(1, 0))
            // Cell (0,0) is vacated by the mover, so the blocker lands there.
            assertEquals(mapOf(2L to Footprint(0, 0)), moves)
        }

        @Test
        fun `a blocker on a busy page goes to the nearest hole, not the first from the top`() {
            // A 4×3 page full but for (0,0), far top-left, and (3,2), right next to the blocker.
            val full = (0 until 12).map { at(it.toLong(), it % 4, it / 4) }
            val items = full.filter {
                it.footprint != Footprint(0, 0) && it.footprint != Footprint(3, 2)
            }
            val mover = at(99, 0, 0) // pretend it stood at (0,0), and drops on (2,2)
            val moves =
                LayoutEngine.displaceFor(
                    grid,
                    items + mover,
                    movingId = 99,
                    target = Footprint(2, 2),
                )
            assertEquals(mapOf(10L to Footprint(3, 2)), moves)
        }

        @Test
        fun `a multi-cell blocker refuses the drop`() {
            val items = listOf(at(1, 0, 0), at(2, 1, 0, 2, 2))
            assertNull(LayoutEngine.displaceFor(grid, items, 1, Footprint(2, 1)))
        }

        @Test
        fun `off-grid target refuses the drop`() {
            assertNull(LayoutEngine.displaceFor(grid, listOf(at(1, 0, 0)), 1, Footprint(4, 0)))
        }

        @Test
        fun `refuses when nothing is free for the blocker`() {
            val full = (0 until 12).map { at(it.toLong(), it % 4, it / 4) }
            val newcomer = at(99, 0, 0) // not on the page yet
            assertNull(LayoutEngine.displaceFor(grid, full + newcomer, 99, Footprint(2, 1)))
        }

        @Test
        fun `a moving widget displaces several single cells, each to the cell nearest it`() {
            val items = listOf(at(1, 0, 0, 2, 1), at(2, 2, 1), at(3, 3, 1))
            val moves = LayoutEngine.displaceFor(grid, items, 1, Footprint(2, 1, 2, 1))
            // Straight up, one cell each; not across to the cells the widget left.
            assertEquals(mapOf(2L to Footprint(2, 0), 3L to Footprint(3, 0)), moves)
        }
    }

    @Nested
    inner class ShiftFor {
        private val row = listOf(at(1, 0, 0), at(2, 1, 0), at(3, 2, 0), at(4, 3, 0))

        @Test
        fun `moving right shifts the slots in between left`() {
            val moves = LayoutEngine.shiftFor(row.filter { it.id != 1L }, from = 0, to = 2)
            assertEquals(mapOf(2L to Footprint(0, 0), 3L to Footprint(1, 0)), moves)
        }

        @Test
        fun `moving left shifts the slots in between right`() {
            val moves = LayoutEngine.shiftFor(row.filter { it.id != 4L }, from = 3, to = 1)
            assertEquals(mapOf(2L to Footprint(2, 0), 3L to Footprint(3, 0)), moves)
        }

        @Test
        fun `an empty target is a plain move, nothing shifts`() {
            // Seven slots, the last empty: slot 5 to slot 7 must not disturb slot 6.
            val dock = (0..5).map { at(it + 1L, it, 0) }
            assertEquals(
                emptyMap<Long, Footprint>(),
                LayoutEngine.shiftFor(dock.filter { it.id != 5L }, from = 4, to = 6),
            )
            assertEquals(emptyMap<Long, Footprint>(), LayoutEngine.shiftFor(row, from = 1, to = 1))
        }

        @Test
        fun `an occupied target shifts only up to the nearest gap`() {
            val gapped = listOf(at(1, 0, 0), at(2, 1, 0), at(4, 3, 0)) // slot 2 empty
            assertEquals(
                mapOf(4L to Footprint(2, 0)),
                LayoutEngine.shiftFor(gapped.filter { it.id != 1L }, from = 0, to = 3),
            )
        }
    }

    @Nested
    inner class PageCountAfterPrune {
        @Test
        fun `drops trailing empty pages only`() {
            assertEquals(
                2,
                LayoutEngine.pageCountAfterPrune(pageCount = 4, nonEmptyPages = setOf(0, 1)),
            )
            assertEquals(
                3,
                LayoutEngine.pageCountAfterPrune(pageCount = 4, nonEmptyPages = setOf(2)),
            )
        }

        @Test
        fun `a container with no pages yet has nothing to prune`() {
            // Reconciliation can run before the first page is written; it must not throw.
            assertEquals(
                0,
                LayoutEngine.pageCountAfterPrune(pageCount = 0, nonEmptyPages = emptySet()),
            )
        }

        @Test
        fun `keeps at least minPages`() {
            assertEquals(
                1,
                LayoutEngine.pageCountAfterPrune(pageCount = 3, nonEmptyPages = emptySet()),
            )
            assertEquals(
                2,
                LayoutEngine.pageCountAfterPrune(
                    pageCount = 3,
                    nonEmptyPages = emptySet(),
                    minPages = 2,
                ),
            )
        }
    }
}
