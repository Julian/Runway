package com.grayvines.runway.ui.home

import com.grayvines.runway.data.Container
import com.grayvines.runway.model.Footprint

/**
 * A drop that has been committed but not yet reflected by the database. Applied to the UI state in
 * the meantime so the dropped icon settles once, rather than snapping back and then jumping.
 */
data class PendingMove(
    val itemId: Long,
    val container: Container,
    val page: Int,
    val x: Int,
    val y: Int,
    val displaced: Map<Long, Footprint>,
)

/**
 * Home and dock pages with [move] applied. Idempotent: applying to already-moved data is a no-op.
 */
fun PendingMove.applyTo(
    home: List<HomePage>,
    dock: List<HomePage>,
): Pair<List<HomePage>, List<HomePage>> {
    val moved =
        (home + dock).flatMap { it.items }.firstOrNull { it.id == itemId } ?: return home to dock
    val relocated = moved.copy(x = x, y = y)
    fun List<HomePage>.without() = map { p -> p.copy(items = p.items.filter { it.id != itemId }) }
    fun List<HomePage>.receiving() = map { p ->
        if (p.index != page) {
            p
        } else {
            val shifted =
                p.items.map { item ->
                    displaced[item.id]?.let { item.copy(x = it.x, y = it.y) } ?: item
                }
            p.copy(items = shifted + relocated)
        }
    }
    return when (container) {
        Container.HOME -> home.without().receiving() to dock.without()
        Container.DOCK -> home.without() to dock.without().receiving()
        Container.DRAWER -> home to dock
    }
}
