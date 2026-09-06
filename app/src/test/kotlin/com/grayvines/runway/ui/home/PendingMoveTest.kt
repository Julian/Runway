package com.grayvines.runway.ui.home

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.drag.PendingMove
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PendingMoveTest {
    private val home =
        listOf(HomePage(0, listOf(item(1, 0, 0), item(2, 1, 0))), HomePage(1, emptyList()))
    private val dock = listOf(HomePage(0, listOf(item(9, 0, 0))))

    private fun item(id: Long, x: Int, y: Int) =
        HomeItem(id, ItemKind.APP, x, y, 1, 1, label = "app$id", app = null)

    private fun cells(pages: List<HomePage>) =
        pages.flatMap { p -> p.items.map { Triple(p.index, it.id, it.x to it.y) } }.toSet()

    @Test
    fun `moves the item within a page and shifts the displaced`() {
        val move = PendingMove(1, Container.HOME, 0, 1, 0, displaced = mapOf(2L to Footprint(0, 0)))
        val (h, d) = move.applyTo(home, dock)
        assertEquals(setOf(Triple(0, 1L, 1 to 0), Triple(0, 2L, 0 to 0)), cells(h))
        assertEquals(cells(dock), cells(d))
    }

    @Test
    fun `moves across pages and containers`() {
        val (h1, _) = PendingMove(1, Container.HOME, 1, 2, 0, emptyMap()).applyTo(home, dock)
        assertEquals(setOf(Triple(0, 2L, 1 to 0), Triple(1, 1L, 2 to 0)), cells(h1))

        val (h2, d2) = PendingMove(9, Container.HOME, 0, 2, 0, emptyMap()).applyTo(home, dock)
        assertEquals(cells(home) + Triple(0, 9L, 2 to 0), cells(h2))
        assertEquals(emptySet<Triple<Int, Long, Pair<Int, Int>>>(), cells(d2))

        val (h3, d3) = PendingMove(1, Container.DOCK, 0, 1, 0, emptyMap()).applyTo(home, dock)
        assertEquals(setOf(Triple(0, 2L, 1 to 0)), cells(h3))
        assertEquals(setOf(Triple(0, 9L, 0 to 0), Triple(0, 1L, 1 to 0)), cells(d3))
    }

    @Test
    fun `is a no-op once the data already reflects it`() {
        val move = PendingMove(1, Container.HOME, 0, 1, 0, displaced = mapOf(2L to Footprint(0, 0)))
        val (once, _) = move.applyTo(home, dock)
        val (twice, _) = move.applyTo(once, dock)
        assertEquals(cells(once), cells(twice))
    }

    @Test
    fun `unknown item leaves everything alone`() {
        val (h, d) = PendingMove(42, Container.HOME, 0, 2, 0, emptyMap()).applyTo(home, dock)
        assertEquals(cells(home), cells(h))
        assertEquals(cells(dock), cells(d))
    }
}
