package com.grayvines.runway.ui.home

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.folderIdentity
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.Placed
import com.grayvines.runway.ui.drag.PendingMove

/**
 * This state with [move] applied. Done in composition, on the same frame the drag state clears, so
 * the dropped icon and its displaced neighbours never revert between the two.
 */
fun HomeState.applying(move: PendingMove?): HomeState {
    if (move == null) return this
    val (home, dock) = move.applyTo(homePages, dockPages)
    return copy(homePages = home, dockPages = dock)
}

/** This state with each of [moves] applied in order. */
fun HomeState.applying(moves: List<PendingMove>): HomeState = moves.fold(this, HomeState::applying)

/**
 * Home and dock pages with [move] applied. Idempotent: applying to already-moved data is a no-op. A
 * fold takes the mover off its page; the folder it went into draws itself once the data shows it. A
 * removal takes it off and puts it nowhere. A mover with no placement yet (an app or widget fresh
 * from the drawer or picker) is not drawn, but the neighbours it displaced move aside all the same:
 * a widget's bind prompt and setup screen can keep its row from being written for as long as the
 * user likes.
 */
fun PendingMove.applyTo(
    home: List<HomePage>,
    dock: List<HomePage>,
): Pair<List<HomePage>, List<HomePage>> {
    val (relocated, relocatedPlaced) = relocate(home + dock)
    fun List<HomePage>.without() = map { it.without(itemId) }
    if (foldInto != null || removed) return home.without() to dock.without()
    fun List<HomePage>.receiving() = map { p ->
        if (p.index != page) p else p.receiving(this@applyTo, relocated, relocatedPlaced)
    }
    return when (container) {
        Container.HOME -> home.without().receiving() to dock.without()
        Container.DOCK -> home.without() to dock.without().receiving()
        Container.DRAWER -> home to dock
    }
}

private fun PendingMove.relocate(pages: List<HomePage>): Pair<HomeItem?, Placed?> {
    val moved = pages.flatMap { it.items }.firstOrNull { it.id == itemId }
    val relocated = moved?.copy(x = x, y = y)
    val movedPlaced =
        pages.flatMap { it.occupied }.firstOrNull { it.id == itemId }
            ?: relocated?.let {
                Placed(it.id, it.footprint, it.kind != ItemKind.WIDGET, it.identity)
            }
            ?: freshPlaced()
    val relocatedPlaced = movedPlaced?.let {
        it.copy(footprint = Footprint(x, y, it.footprint.width, it.footprint.height))
    }
    return relocated to relocatedPlaced
}

private fun PendingMove.freshPlaced(): Placed? =
    when {
        newWidget != null -> Placed(0L, Footprint(x, y, spanX, spanY), foldable = false)
        newApp != null -> Placed(0L, Footprint(x, y), identity = newApp.identity)
        newFolder != null -> Placed(0L, Footprint(x, y), identity = folderIdentity(newFolder))
        else -> null
    }

private fun HomePage.without(itemId: Long): HomePage =
    if (itemId == 0L) {
        this
    } else {
        copy(
            items = items.filter { it.id != itemId },
            occupied = occupied.filter { it.id != itemId },
        )
    }

private fun HomePage.receiving(
    move: PendingMove,
    relocated: HomeItem?,
    relocatedPlaced: Placed?,
): HomePage {
    val shifted = items.map { item ->
        move.displaced[item.id]?.let { item.copy(x = it.x, y = it.y) } ?: item
    }
    val shiftedOccupied = occupied.map { placed ->
        move.displaced[placed.id]?.let {
            placed.copy(
                footprint = Footprint(it.x, it.y, placed.footprint.width, placed.footprint.height)
            )
        } ?: placed
    }
    return copy(
        items = shifted + listOfNotNull(relocated),
        occupied = shiftedOccupied + listOfNotNull(relocatedPlaced),
    )
}
