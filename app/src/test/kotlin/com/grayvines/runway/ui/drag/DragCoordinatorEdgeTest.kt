package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DragCoordinatorEdgeTest {
    private val source = DragSource(1, ItemKind.APP, Container.HOME, 0, 0, 0)
    private val grab = Point(50f, 50f)

    @Test
    fun `a cancel mid-scroll emits an exact return to source page even when shown still matches`() =
        runTest {
            val c = DragCoordinator(backgroundScope, lookup, FakeWorkspace())
            c.layOut()
            val flips = mutableListOf<PageFlip>()
            backgroundScope.launch { c.flipHomePage.collect { flips += it } }
            runCurrent()
            c.startDrag(source, Point(50f, 50f), grab)
            c.areas.homePageShown(0, 3, 2, settled = false) // scroll away from page 0 in flight
            c.cancelDrag()
            runCurrent()
            assertEquals(listOf(PageFlip.To(0)), flips)
        }

    @Test
    fun `a duplicate endDrag while waiting on a scrolling page does not restart the settle wait`() =
        runTest {
            val workspace = FakeWorkspace()
            val c = DragCoordinator(backgroundScope, lookup, workspace)
            c.layOut()
            c.startDrag(source, Point(50f, 50f), grab)
            c.dragTo(Point(250f, 150f))
            c.areas.homePageShown(0, 3, 2, settled = false)
            c.endDrag()
            advanceTimeBy(DragCoordinator.SETTLE_WAIT_MS / 2)
            c.endDrag() // stray release while already waiting
            advanceTimeBy(DragCoordinator.SETTLE_WAIT_MS / 2 + 1)
            assertEquals(1, workspace.moves.size)
            assertNull(c.drag.value)
        }

    @Test
    fun `dwelling at the left edge on page 0 emits no flips`() = runTest {
        val workspace = FakeWorkspace(pages = 2)
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        val flips = mutableListOf<PageFlip>()
        backgroundScope.launch { c.flipHomePage.collect { flips += it } }
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(150f, 50f)) // arm edges
        c.dragTo(Point(1f, 50f)) // left edge of page 0
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 2)
        assertEquals(emptyList<PageFlip>(), flips)
        c.cancelDrag()
    }

    @Test
    fun `dwelling at the right edge flips, and past the end adds a page`() = runTest {
        val workspace = FakeWorkspace(pages = 2)
        val c = DragCoordinator(backgroundScope, lookup, workspace)
        val flips = mutableListOf<PageFlip>()
        backgroundScope.launch { c.flipHomePage.collect { flips += it } }
        c.layOut()
        c.startDrag(source, Point(50f, 50f), grab)
        c.dragTo(Point(150f, 50f))
        c.dragTo(Point(299f, 50f)) // right edge of page 0
        advanceTimeBy(EdgeDwell.FLIP_MS + 1)
        assertEquals(listOf(PageFlip.By(1)), flips)
        c.areas.homePagePositioned(1, Bounds(0f, 0f, 300f, 200f), 3, 2)
        c.areas.homePageShown(1, 3, 2) // now on the last page
        // Up to the tick that adds the page, and not the one after: the fake's shown page only
        // advances when told, and until it does that page is no longer the last.
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS)
        assertEquals(3, workspace.pages)
        assertEquals(
            listOf(PageFlip.By(1), PageFlip.By(1)),
            flips,
        ) // the second flip is onto the new page
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
            val homeFlips = mutableListOf<PageFlip>()
            val dockFlips = mutableListOf<PageFlip>()
            backgroundScope.launch { c.flipHomePage.collect { homeFlips += it } }
            backgroundScope.launch { c.flipDockPage.collect { dockFlips += it } }
            c.layOut()
            c.startDrag(source, Point(50f, 50f), grab)
            c.dragTo(Point(150f, 225f))
            c.dragTo(Point(299f, 225f)) // right edge of the only dock page
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS + EdgeDwell.FLIP_MS)
            assertEquals(2, workspace.dockPages)
            assertEquals(listOf(PageFlip.By(1)), dockFlips)
            assertEquals(emptyList<PageFlip>(), homeFlips)
            assertEquals(2, workspace.pages) // the home pages are untouched
            c.areas.dockPagePositioned(1, Bounds(0f, 200f, 300f, 250f), 1)
            c.areas.dockPageShown(1, 1)
            c.dragTo(Point(1f, 225f)) // over to the left edge: flips back, never adds
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 2)
            assertEquals(2, workspace.dockPages)
            assertEquals(PageFlip.By(-1), dockFlips[1])
            c.cancelDrag()
        }

    @Test
    fun `an item lifted inside an edge zone flips nothing until the finger has left the zone`() =
        runTest {
            val workspace = FakeWorkspace(pages = 1, dockPages = 1)
            val c = DragCoordinator(backgroundScope, lookup, workspace)
            val flips = mutableListOf<PageFlip>()
            backgroundScope.launch { c.flipDockPage.collect { flips += it } }
            c.layOut()
            val inTheZone = Point(295f, 225f) // the last dock slot's icon sits here
            c.startDrag(source.copy(container = Container.DOCK, x = 2), inTheZone, grab)
            c.dragTo(inTheZone)
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 3)
            assertEquals(1, workspace.dockPages)
            assertEquals(emptyList<PageFlip>(), flips)
            c.dragTo(Point(150f, 225f)) // away, then back to the edge: now it counts
            c.dragTo(inTheZone)
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS + EdgeDwell.FLIP_MS)
            assertEquals(2, workspace.dockPages)
            assertEquals(listOf(PageFlip.By(1)), flips)
            c.cancelDrag()
        }
}
