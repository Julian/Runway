package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import kotlinx.coroutines.CompletableDeferred

/** Two 3×2 home pages; item 1 at (0,0) on page 0, item 2 at (1,0) on page 0; one dock slot. */
internal val lookup =
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

internal class FakeWorkspace(
    var pages: Int = 2,
    var dockPages: Int = 1,
    val moveSucceeds: Boolean = true,
) : DragWorkspace {
    val moves = mutableListOf<PendingMove>()
    var reflected = CompletableDeferred<Unit>()
    var prunes = 0

    override fun pageCount(container: Container) =
        if (container == Container.DOCK) dockPages else pages

    override suspend fun move(move: PendingMove): Boolean {
        moves += move
        return moveSucceeds
    }

    override suspend fun awaitReflected(move: PendingMove) = reflected.await()

    override suspend fun pruneEmptyPages() {
        prunes++
    }

    override suspend fun addPage(container: Container, index: Int): Boolean {
        if (container == Container.DOCK) {
            dockPages = maxOf(dockPages, index + 1)
        } else {
            pages = maxOf(pages, index + 1)
        }
        return true
    }
}

/** Page 0 holds the app in cell (0,0), as item 1. */
internal fun holding(app: AppRef) =
    object : WorkspaceLookup {
        override val grid = GridSize(3, 2)
        override val dockSlots = 1

        override fun homeItems(page: Int) =
            if (page == 0) {
                listOf(Placed(1, Footprint(0, 0), identity = app.identity))
            } else {
                emptyList()
            }

        override fun dockItems(page: Int) = emptyList<Placed>()
    }

internal fun fromDrawer(app: AppRef) =
    DragSource(
        0,
        ItemKind.APP,
        Container.DRAWER,
        0,
        0,
        0,
        newApp = app,
        identity = app.identity,
    )

/** Home area 300×200 at the top, dock 300×50 below it; 3 columns, 2 rows. */
internal fun DragCoordinator.layOut() {
    areas.homePagePositioned(0, Bounds(0f, 0f, 300f, 200f), 3, 2)
    areas.homePageShown(0, 3, 2)
    areas.dockPagePositioned(0, Bounds(0f, 200f, 300f, 250f), 1)
}
