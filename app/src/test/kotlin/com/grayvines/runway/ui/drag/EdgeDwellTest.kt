package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeDwellTest {
    /** A workspace of [pages] pages; the finger is "on" [page]. */
    private class Fake(
        var pages: Int,
        var page: Int,
        val addFails: Boolean = false,
        var dockPages: Int = 1,
        var dockPage: Int = 0,
    ) : EdgeDwell.Actions {
        val events = mutableListOf<String>()
        val containers = mutableListOf<Container>()

        override fun isPastTheEnd(hover: EdgeHover): Boolean {
            val (shown, count) =
                if (hover.container == Container.DOCK) dockPage to dockPages else page to pages
            return hover.edge == Edge.RIGHT && shown >= count - 1
        }

        override fun flip(container: Container, delta: Int) {
            if (container == Container.DOCK) {
                dockPage = (dockPage + delta).coerceIn(0, dockPages - 1)
            } else {
                page = (page + delta).coerceIn(0, pages - 1)
            }
            containers += container
            events += "flip$delta"
        }

        override suspend fun addPage(container: Container): Boolean {
            containers += container
            events += "add"
            if (addFails) return false
            if (container == Container.DOCK) dockPages++ else pages++
            return true
        }
    }

    @Test
    fun `a failed page add is not retried and does not flip`() = runTest {
        val fake = Fake(pages = 1, page = 0, addFails = true)
        EdgeDwell(backgroundScope, fake).hover(EdgeHover(Container.HOME, Edge.RIGHT))
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 3)
        assertEquals(listOf("add"), fake.events)
    }

    @Test
    fun `flips once per dwell while held at an edge`() = runTest {
        val fake = Fake(pages = 4, page = 0)
        val dwell = EdgeDwell(backgroundScope, fake)
        dwell.hover(EdgeHover(Container.HOME, Edge.RIGHT))
        advanceTimeBy(EdgeDwell.FLIP_MS * 3 + 1)
        assertEquals(listOf("flip1", "flip1", "flip1"), fake.events)
        dwell.stop()
        advanceTimeBy(EdgeDwell.FLIP_MS * 3)
        assertEquals(3, fake.events.size)
    }

    @Test
    fun `at the end, a long hold adds one page and flips to it, once`() = runTest {
        val fake = Fake(pages = 2, page = 1)
        EdgeDwell(backgroundScope, fake).hover(EdgeHover(Container.HOME, Edge.RIGHT))
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS) // one tick short
        assertEquals(emptyList<String>(), fake.events)
        advanceTimeBy(EdgeDwell.FLIP_MS)
        assertEquals(listOf("add", "flip1"), fake.events)
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 3)
        assertEquals(listOf("add", "flip1"), fake.events) // still just one page per hold
    }

    @Test
    fun `the long hold is counted from arriving at the end, not from the start of the drag`() =
        runTest {
            val fake = Fake(pages = 4, page = 0)
            EdgeDwell(backgroundScope, fake).hover(EdgeHover(Container.HOME, Edge.RIGHT))
            advanceTimeBy(EdgeDwell.FLIP_MS * 3 + 1) // flips 0 → 3, the last page
            assertEquals(3, fake.events.count { it == "flip1" })
            advanceTimeBy(EdgeDwell.FLIP_MS * 2) // 900 ms at the end: not yet
            assertEquals(0, fake.events.count { it == "add" })
            advanceTimeBy(EdgeDwell.FLIP_MS) // 1350 ms at the end: now
            assertEquals(1, fake.events.count { it == "add" })
        }

    @Test
    fun `leaving and returning to the edge restarts the hold`() = runTest {
        val fake = Fake(pages = 1, page = 0)
        val dwell = EdgeDwell(backgroundScope, fake)
        dwell.hover(EdgeHover(Container.HOME, Edge.RIGHT))
        advanceTimeBy(EdgeDwell.FLIP_MS * 2 + 1)
        dwell.hover(null)
        dwell.hover(EdgeHover(Container.HOME, Edge.RIGHT))
        advanceTimeBy(EdgeDwell.FLIP_MS * 2 + 1)
        assertEquals(emptyList<String>(), fake.events)
        advanceTimeBy(EdgeDwell.FLIP_MS)
        assertEquals(listOf("add", "flip1"), fake.events)
    }

    @Test
    fun `the left edge on the first page does nothing harmful`() = runTest {
        val fake = Fake(pages = 3, page = 0)
        EdgeDwell(backgroundScope, fake).hover(EdgeHover(Container.HOME, Edge.LEFT))
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 2)
        assertEquals(0, fake.events.count { it == "add" })
        assertEquals(0, fake.page)
    }

    @Test
    fun `switching between home and dock edges restarts the dwell for the new container`() =
        runTest {
            val fake = Fake(pages = 3, page = 0, dockPages = 1, dockPage = 0)
            val dwell = EdgeDwell(backgroundScope, fake)
            dwell.hover(EdgeHover(Container.HOME, Edge.RIGHT))
            advanceTimeBy(EdgeDwell.FLIP_MS + 1)
            assertEquals(listOf("flip1"), fake.events)
            assertEquals(listOf(Container.HOME), fake.containers)

            // Switch straight to the dock's right edge (already at the end of its single page).
            dwell.hover(EdgeHover(Container.DOCK, Edge.RIGHT))
            advanceTimeBy(EdgeDwell.ADD_PAGE_MS) // one tick short on the dock
            assertEquals(listOf("flip1"), fake.events)
            advanceTimeBy(EdgeDwell.FLIP_MS)
            assertEquals(listOf("flip1", "add", "flip1"), fake.events)
            assertEquals(
                listOf(Container.HOME, Container.DOCK, Container.DOCK),
                fake.containers,
            )
            assertEquals(2, fake.dockPages)
            assertEquals(1, fake.dockPage)
            assertEquals(1, fake.page)
        }
}
