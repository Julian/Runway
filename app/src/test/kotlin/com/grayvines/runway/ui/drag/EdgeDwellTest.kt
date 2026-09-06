package com.grayvines.runway.ui.drag

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EdgeDwellTest {
    /** A workspace of [pages] pages; the finger is "on" [page]. */
    private class Fake(var pages: Int, var page: Int, val addFails: Boolean = false) :
        EdgeDwell.Actions {
        val events = mutableListOf<String>()

        override fun isPastTheEnd(edge: Edge) = edge == Edge.RIGHT && page >= pages - 1

        override fun flip(delta: Int) {
            page = (page + delta).coerceIn(0, pages - 1)
            events += "flip$delta"
        }

        override suspend fun addPage(): Boolean {
            events += "add"
            if (addFails) return false
            pages++
            return true
        }
    }

    @Test
    fun `a failed page add is not retried and does not flip`() = runTest {
        val fake = Fake(pages = 1, page = 0, addFails = true)
        EdgeDwell(backgroundScope, fake).hover(Edge.RIGHT)
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 3)
        assertEquals(listOf("add"), fake.events)
    }

    @Test
    fun `flips once per dwell while held at an edge`() = runTest {
        val fake = Fake(pages = 4, page = 0)
        val dwell = EdgeDwell(backgroundScope, fake)
        dwell.hover(Edge.RIGHT)
        advanceTimeBy(EdgeDwell.FLIP_MS * 3 + 1)
        assertEquals(listOf("flip1", "flip1", "flip1"), fake.events)
        dwell.stop()
        advanceTimeBy(EdgeDwell.FLIP_MS * 3)
        assertEquals(3, fake.events.size)
    }

    @Test
    fun `at the end, a long hold adds one page and flips to it, once`() = runTest {
        val fake = Fake(pages = 2, page = 1)
        EdgeDwell(backgroundScope, fake).hover(Edge.RIGHT)
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
            EdgeDwell(backgroundScope, fake).hover(Edge.RIGHT)
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
        dwell.hover(Edge.RIGHT)
        advanceTimeBy(EdgeDwell.FLIP_MS * 2 + 1)
        dwell.hover(null)
        dwell.hover(Edge.RIGHT)
        advanceTimeBy(EdgeDwell.FLIP_MS * 2 + 1)
        assertEquals(emptyList<String>(), fake.events)
        advanceTimeBy(EdgeDwell.FLIP_MS)
        assertEquals(listOf("add", "flip1"), fake.events)
    }

    @Test
    fun `the left edge on the first page does nothing harmful`() = runTest {
        val fake = Fake(pages = 3, page = 0)
        EdgeDwell(backgroundScope, fake).hover(Edge.LEFT)
        advanceTimeBy(EdgeDwell.ADD_PAGE_MS * 2)
        assertEquals(0, fake.events.count { it == "add" })
        assertEquals(0, fake.page)
    }
}
