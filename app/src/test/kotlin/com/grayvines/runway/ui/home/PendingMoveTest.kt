package com.grayvines.runway.ui.home

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.Placed
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.PendingMove
import com.grayvines.runway.ui.menu.ItemMenuState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PendingMoveTest {
    private val home =
        listOf(HomePage(0, listOf(item(1, 0, 0), item(2, 1, 0))), HomePage(1, emptyList()))
    private val dock = listOf(HomePage(0, listOf(item(9, 0, 0))))
    private val state = HomeState(homePages = home, dockPages = dock)
    private val anchor = Bounds(0f, 0f, 10f, 10f)

    private fun item(id: Long, x: Int, y: Int) =
        HomeItem(id, ItemKind.APP, x, y, 1, 1, label = "app$id", app = null)

    private fun cells(pages: List<HomePage>) =
        pages.flatMap { p -> p.items.map { Triple(p.index, it.id, it.x to it.y) } }.toSet()

    private fun occupiedCells(pages: List<HomePage>) =
        pages
            .flatMap { p ->
                p.occupied.map { Triple(p.index, it.id, it.footprint.x to it.footprint.y) }
            }
            .toSet()

    private fun stateWith(extra: HomeItem) =
        state.copy(homePages = listOf(HomePage(0, home[0].items + extra), home[1]))

    @Test
    fun `moves the item within a page and shifts the displaced`() {
        val move = PendingMove(1, Container.HOME, 0, 1, 0, displaced = mapOf(2L to Footprint(0, 0)))
        val (h, d) = move.applyTo(home, dock)
        assertEquals(setOf(Triple(0, 1L, 1 to 0), Triple(0, 2L, 0 to 0)), cells(h))
        assertEquals(setOf(Triple(0, 1L, 1 to 0), Triple(0, 2L, 0 to 0)), occupiedCells(h))
        assertEquals(cells(dock), cells(d))
        assertEquals(occupiedCells(dock), occupiedCells(d))
    }

    @Test
    fun `reorders the dock and shifts the displaced slots`() {
        val three = listOf(HomePage(0, listOf(item(7, 0, 0), item(8, 1, 0), item(9, 2, 0))))
        val move =
            PendingMove(
                7,
                Container.DOCK,
                0,
                2,
                0,
                displaced = mapOf(8L to Footprint(0, 0), 9L to Footprint(1, 0)),
            )
        val (h, d) = move.applyTo(home, three)
        assertEquals(cells(home), cells(h))
        assertEquals(
            setOf(Triple(0, 7L, 2 to 0), Triple(0, 8L, 0 to 0), Triple(0, 9L, 1 to 0)),
            cells(d),
        )
    }

    @Test
    fun `moves across pages and containers`() {
        val (h1, _) = PendingMove(1, Container.HOME, 1, 2, 0, emptyMap()).applyTo(home, dock)
        assertEquals(setOf(Triple(0, 2L, 1 to 0), Triple(1, 1L, 2 to 0)), cells(h1))
        assertEquals(setOf(Triple(0, 2L, 1 to 0), Triple(1, 1L, 2 to 0)), occupiedCells(h1))

        val (h2, d2) = PendingMove(9, Container.HOME, 0, 2, 0, emptyMap()).applyTo(home, dock)
        assertEquals(cells(home) + Triple(0, 9L, 2 to 0), cells(h2))
        assertEquals(occupiedCells(home) + Triple(0, 9L, 2 to 0), occupiedCells(h2))
        assertEquals(emptySet<Triple<Int, Long, Pair<Int, Int>>>(), cells(d2))
        assertEquals(emptySet<Triple<Int, Long, Pair<Int, Int>>>(), occupiedCells(d2))

        val (h3, d3) = PendingMove(1, Container.DOCK, 0, 1, 0, emptyMap()).applyTo(home, dock)
        assertEquals(setOf(Triple(0, 2L, 1 to 0)), cells(h3))
        assertEquals(setOf(Triple(0, 2L, 1 to 0)), occupiedCells(h3))
        assertEquals(setOf(Triple(0, 9L, 0 to 0), Triple(0, 1L, 1 to 0)), cells(d3))
        assertEquals(setOf(Triple(0, 9L, 0 to 0), Triple(0, 1L, 1 to 0)), occupiedCells(d3))
    }

    @Test
    fun `updates occupied preserving foldable and identity including across pages`() {
        val pages =
            listOf(
                HomePage(
                    index = 0,
                    items = listOf(HomeItem(1, ItemKind.WIDGET, 0, 0, 2, 2, "w", null)),
                    occupied =
                        listOf(
                            Placed(1, Footprint(0, 0, 2, 2), foldable = false, identity = null),
                            Placed(2, Footprint(2, 0, 1, 1), foldable = true, identity = "app:2"),
                        ),
                ),
                HomePage(
                    index = 1,
                    items = emptyList(),
                    occupied =
                        listOf(
                            Placed(3, Footprint(1, 1, 1, 1), foldable = true, identity = "app:3")
                        ),
                ),
            )
        val move =
            PendingMove(
                itemId = 1,
                container = Container.HOME,
                page = 1,
                x = 1,
                y = 1,
                displaced = mapOf(3L to Footprint(0, 2)),
            )
        val (h, _) = move.applyTo(pages, dock)
        assertEquals(
            listOf(Placed(2, Footprint(2, 0, 1, 1), foldable = true, identity = "app:2")),
            h[0].occupied,
        )
        assertEquals(
            listOf(
                Placed(3, Footprint(0, 2, 1, 1), foldable = true, identity = "app:3"),
                Placed(1, Footprint(1, 1, 2, 2), foldable = false, identity = null),
            ),
            h[1].occupied,
        )
    }

    @Test
    fun `a fold takes the mover off its page and leaves the target where it is`() {
        val fold = PendingMove(1, Container.HOME, 0, 1, 0, emptyMap(), foldInto = 2L)
        val (h, d) = fold.applyTo(home, dock)
        assertEquals(setOf(Triple(0, 2L, 1 to 0)), cells(h))
        assertEquals(cells(dock), cells(d))
    }

    @Test
    fun `a removal takes the item off its page and moves nothing else`() {
        val removal = PendingMove(9, Container.DOCK, 0, 0, 0, emptyMap(), removed = true)
        val (h, d) = removal.applyTo(home, dock)
        assertEquals(cells(home), cells(h))
        assertEquals(emptySet<Triple<Int, Long, Pair<Int, Int>>>(), cells(d))
        val (again, _) = removal.applyTo(h, d)
        assertEquals(cells(h), cells(again))
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

    @Test
    fun `a mover with no placement yet still moves the neighbours it displaced`() {
        // A widget dropped out of the picker has no row until it is bound and set up, which can
        // take as long as the user likes; the icons it pushed aside must not snap back meanwhile.
        val move = PendingMove(0, Container.HOME, 0, 0, 0, displaced = mapOf(1L to Footprint(2, 0)))
        val (h, d) = move.applyTo(home, dock)
        assertEquals(setOf(Triple(0, 1L, 2 to 0), Triple(0, 2L, 1 to 0)), cells(h))
        assertEquals(cells(dock), cells(d))
        val (again, _) = move.applyTo(h, dock)
        assertEquals(cells(h), cells(again))
    }

    @Test
    fun `reflects checks removals, folds, and moves of placed items`() {
        val removal = PendingMove(1, Container.HOME, 0, 0, 0, emptyMap(), removed = true)
        assertFalse(state.reflects(removal))
        assertTrue(state.applying(removal).reflects(removal))

        val foldPlaced = PendingMove(1, Container.HOME, 0, 1, 0, emptyMap(), foldInto = 2L)
        assertFalse(state.reflects(foldPlaced))
        assertTrue(state.applying(foldPlaced).reflects(foldPlaced))

        val moveHome = PendingMove(1, Container.HOME, 0, 2, 0, emptyMap())
        assertFalse(state.reflects(moveHome))
        assertTrue(state.applying(moveHome).reflects(moveHome))

        val moveDock = PendingMove(9, Container.DOCK, 0, 1, 0, emptyMap())
        assertFalse(state.reflects(moveDock))
        assertTrue(state.applying(moveDock).reflects(moveDock))
    }

    @Test
    fun `reflects checks freshly placed folders and widgets`() {
        val placeFolder = PendingMove(0, Container.HOME, 0, 2, 0, emptyMap(), newFolder = 7L)
        assertFalse(state.reflects(placeFolder))
        val folder = HomeItem(5, ItemKind.FOLDER, 2, 0, 1, 1, "Work", null, folderId = 7L)
        assertTrue(stateWith(folder).reflects(placeFolder))
        assertFalse(stateWith(folder.copy(folderId = 8L)).reflects(placeFolder))

        val placeWidget =
            PendingMove(0, Container.HOME, 0, 2, 0, emptyMap(), newWidget = "0/pkg/.Widget")
        assertFalse(state.reflects(placeWidget))
        val widget =
            HomeItem(6, ItemKind.WIDGET, 2, 0, 2, 1, "Widget", null, provider = "pkg/.Widget")
        assertTrue(stateWith(widget).reflects(placeWidget))
        assertFalse(stateWith(widget.copy(provider = "pkg/.Other")).reflects(placeWidget))
    }

    @Test
    fun `stillHas checks placed home items, dock items, and drawer folders`() {
        val homeMenu = ItemMenuState(item(1, 0, 0), Container.HOME, 0, anchor)
        assertTrue(state.stillHas(homeMenu))
        assertFalse(HomeState().stillHas(homeMenu))

        val dockMenu = ItemMenuState(item(9, 0, 0), Container.DOCK, 0, anchor)
        assertTrue(state.stillHas(dockMenu))
        assertFalse(HomeState().stillHas(dockMenu))

        val drawerFolder =
            HomeItem(11, ItemKind.FOLDER, 0, 0, 1, 1, "Work", null, folderId = 7L, inDrawer = true)
        val drawerFolderMenu = ItemMenuState(drawerFolder, Container.DRAWER, 0, anchor)
        assertTrue(HomeState(drawerFolders = listOf(drawerFolder)).stillHas(drawerFolderMenu))
        assertFalse(HomeState().stillHas(drawerFolderMenu))
    }
}
