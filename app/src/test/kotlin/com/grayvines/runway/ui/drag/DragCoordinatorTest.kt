package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DragCoordinatorTest {
    /** Two 3×2 home pages; item 1 at (0,0) on page 0, item 2 at (1,0) on page 0; one dock slot. */
    private val lookup =
        object : WorkspaceLookup {
            override val grid = GridSize(3, 2)
            override val dockSlots = 1

            override fun homeItems(page: Int) =
                if (page == 0) {
                    listOf(Placed(1, Footprint(0, 0)), Placed(2, Footprint(1, 0)))
                } else {
                    emptyList()
                }

            override fun dockItems(page: Int) = emptyList<Placed>()
        }

    private class FakeWorkspace(
        var pages: Int = 2,
        var dockPages: Int = 1,
        val moveSucceeds: Boolean = true,
    ) : DragWorkspace {
        val moves = mutableListOf<PendingMove>()
        val reflected = CompletableDeferred<Unit>()

        override fun pageCount(container: Container) =
            if (container == Container.DOCK) dockPages else pages

        override suspend fun move(move: PendingMove): Boolean {
            moves += move
            return moveSucceeds
        }

        override suspend fun awaitReflected(move: PendingMove) = reflected.await()

        override suspend fun addPage(container: Container, index: Int): Boolean {
            if (container == Container.DOCK) {
                dockPages = maxOf(dockPages, index + 1)
            } else {
                pages = maxOf(pages, index + 1)
            }
            return true
        }
    }

    private val source = DragSource(1, ItemKind.APP, Container.HOME, 0, 0, 0)
    private val grab = Point(50f, 50f)

    // Home area 300×200 at the top, dock 300×50 below it; 3 columns, 2 rows.
    private fun DragCoordinator.layOut() {
        areas.homePagePositioned(0, Bounds(0f, 0f, 300f, 200f), 3, 2)
        areas.homePageShown(0, 3, 2)
        areas.dockPagePositioned(0, Bounds(0f, 200f, 300f, 250f), 1)
    }

    @Test
    fun `a drop shows the pending move until the database reflects it`() = runTest {
        val workspace = FakeWorkspace()
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(250f, 150f)) // cell (2,1), free
        c.endDrag()
        runCurrent()
        // Released with the pointer at (250,150) and the grab at (50,50): the cell's corner was
        // (200,100).
        val expected = PendingMove(1, Container.HOME, 0, 2, 1, emptyMap(), from = Point(200f, 100f))
        assertEquals(listOf(expected), workspace.moves)
        assertEquals(expected, c.pending.value)
        assertNull(c.drag.value)
        workspace.reflected.complete(Unit)
        runCurrent()
        assertNull(c.pending.value)
    }

    @Test
    fun `a dock reorder writes the shifted neighbours too`() = runTest {
        val dockLookup =
            object : WorkspaceLookup by lookup {
                override val dockSlots = 3

                override fun dockItems(page: Int) =
                    listOf(
                        Placed(7, Footprint(0, 0)),
                        Placed(8, Footprint(1, 0)),
                        Placed(9, Footprint(2, 0)),
                    )
            }
        val workspace = FakeWorkspace()
        val c = DragCoordinator(backgroundScope, dockLookup, workspace)
        c.layOut()
        c.areas.dockPagePositioned(0, Bounds(0f, 200f, 300f, 250f), 3)
        c.startDrag(DragSource(7, ItemKind.APP, Container.DOCK, 0, 0, 0), Point(50f, 225f), grab)
        c.dragTo(Point(250f, 225f)) // slot 2
        c.endDrag()
        runCurrent()
        val expected =
            PendingMove(
                7,
                Container.DOCK,
                0,
                2,
                0,
                mapOf(8L to Footprint(0, 0), 9L to Footprint(1, 0)),
                from = Point(200f, 175f),
            )
        assertEquals(listOf(expected), workspace.moves)
    }

    @Test
    fun `the settle signal outlives a fast write and ends when the UI says so`() = runTest {
        val workspace = FakeWorkspace()
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(250f, 150f))
        c.endDrag()
        workspace.reflected.complete(Unit) // the database is faster than a frame
        runCurrent()
        assertNull(c.pending.value)
        assertEquals(Settling(1, Point(200f, 100f)), c.settling.value)
        c.settled(99) // some other item: ignored
        assertEquals(Settling(1, Point(200f, 100f)), c.settling.value)
        c.settled(1)
        assertNull(c.settling.value)
    }

    @Test
    fun `an undrawn settle is dropped after a timeout`() = runTest {
        val c = DragCoordinator(backgroundScope, lookup, FakeWorkspace())
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(250f, 150f))
        c.endDrag()
        advanceTimeBy(2_001)
        assertNull(c.settling.value)
    }

    @Test
    fun `a failed write clears the pending move without waiting`() = runTest {
        val workspace = FakeWorkspace(moveSucceeds = false)
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(250f, 150f))
        c.endDrag()
        runCurrent()
        assertNull(c.pending.value) // not stuck on awaitReflected, which never completes here
    }

    @Test
    fun `an invalid or missing target drops nothing but still settles back`() = runTest {
        val workspace = FakeWorkspace()
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(150f, 300f)) // below everything
        c.endDrag()
        runCurrent()
        assertEquals(emptyList<PendingMove>(), workspace.moves)
        assertNull(c.pending.value)
        assertEquals(Settling(1, Point(100f, 250f)), c.settling.value) // slides home from here
    }

    @Test
    fun `a still finger is re-planned when the page under it changes`() = runTest {
        val workspace = FakeWorkspace()
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(150f, 50f)) // cell (1,0) on page 0: occupied, so item 2 is displaced
        assertEquals(DropTarget.HomeCell(0, 1, 0), c.drag.value?.target)
        assertEquals(mapOf(2L to Footprint(0, 0)), (c.drag.value?.plan as DropPlan.Move).displaced)

        c.areas.homePagePositioned(1, Bounds(0f, 0f, 300f, 200f), 3, 2)
        c.areas.homePageShown(1, 3, 2) // the pager flipped under the finger
        assertEquals(DropTarget.HomeCell(1, 1, 0), c.drag.value?.target)
        assertEquals(emptyMap<Long, Footprint>(), (c.drag.value?.plan as DropPlan.Move).displaced)
    }

    @Test
    fun `dwelling at the right edge flips, and past the end adds a page`() = runTest {
        val workspace = FakeWorkspace(pages = 2)
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        val flips = mutableListOf<Int>()
        backgroundScope.launch { c.flipHomePage.collect { flips += it } }
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(150f, 50f))
        c.dragTo(Point(299f, 50f)) // right edge of page 0
        advanceTimeBy(EdgeDwell.FLIP_MS + 1)
        assertEquals(listOf(1), flips)
        c.areas.homePagePositioned(1, Bounds(0f, 0f, 300f, 200f), 3, 2)
        c.areas.homePageShown(1, 3, 2) // now on the last page
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS + EdgeDwell.FLIP_MS)
        assertEquals(3, workspace.pages)
        assertEquals(listOf(1, 1), flips.take(2)) // the second flip is onto the new page
        c.areas.homePagePositioned(2, Bounds(0f, 0f, 300f, 200f), 3, 2)
        c.areas.homePageShown(2, 3, 2)
        val flipsSoFar = flips.size
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 2)
        assertEquals(3, workspace.pages) // one page per hold
        assertEquals(flipsSoFar, flips.size) // and nothing to flip to
        c.cancelDrag()
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 2)
        assertEquals(3, workspace.pages) // nothing after cancel
    }

    @Test
    fun `dwelling at the dock's edge flips dock pages, and past the end adds a dock page`() =
        runTest {
            val workspace = FakeWorkspace(pages = 2, dockPages = 1)
            val c = DragCoordinator(backgroundScope, lookup, workspace)
            val homeFlips = mutableListOf<Int>()
            val dockFlips = mutableListOf<Int>()
            backgroundScope.launch { c.flipHomePage.collect { homeFlips += it } }
            backgroundScope.launch { c.flipDockPage.collect { dockFlips += it } }
            c.layOut()
            c.startDrag(source, Point(50f, 50f), grab)
            c.dragTo(Point(150f, 225f))
            c.dragTo(Point(299f, 225f)) // right edge of the only dock page
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS + EdgeDwell.FLIP_MS)
            assertEquals(2, workspace.dockPages)
            assertEquals(listOf(1), dockFlips)
            assertEquals(emptyList<Int>(), homeFlips)
            assertEquals(2, workspace.pages) // the home pages are untouched
            c.areas.dockPagePositioned(1, Bounds(0f, 200f, 300f, 250f), 1)
            c.areas.dockPageShown(1, 1)
            c.dragTo(Point(1f, 225f)) // over to the left edge: flips back, never adds
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 2)
            assertEquals(2, workspace.dockPages)
            assertEquals(-1, dockFlips[1])
            c.cancelDrag()
        }

    @Test
    fun `an item lifted inside an edge zone flips nothing until the finger has left the zone`() =
        runTest {
            val workspace = FakeWorkspace(pages = 1, dockPages = 1)
            val c = DragCoordinator(backgroundScope, lookup, workspace)
            val flips = mutableListOf<Int>()
            backgroundScope.launch { c.flipDockPage.collect { flips += it } }
            c.layOut()
            val inTheZone = Point(295f, 225f) // the last dock slot's icon sits here
            c.startDrag(source.copy(container = Container.DOCK, x = 2), inTheZone, grab)
            c.dragTo(inTheZone)
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 3)
            assertEquals(1, workspace.dockPages)
            assertEquals(emptyList<Int>(), flips)
            c.dragTo(Point(150f, 225f)) // away, then back to the edge: now it counts
            c.dragTo(inTheZone)
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS + EdgeDwell.FLIP_MS)
            assertEquals(2, workspace.dockPages)
            assertEquals(listOf(1), flips)
            c.cancelDrag()
        }
}
