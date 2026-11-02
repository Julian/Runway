package com.grayvines.runway.data

import com.grayvines.runway.model.Footprint

/*
 * Widget placements. A widget lives on a home page only, as the id the widget host allocated for
 * it plus the provider it was bound to, kept so a widget whose app is gone can still be named.
 */

/**
 * A widget the host has bound, over [spanX] × [spanY] cells of home [page] from ([x], [y]), after
 * moving aside the neighbours it [displaced]; null, and nothing changed, if they could not be
 * moved.
 */
suspend fun WorkspaceRepository.addWidget(
    appWidgetId: Int?,
    provider: String,
    page: Int,
    x: Int,
    y: Int,
    spanX: Int,
    spanY: Int,
    displaced: Map<Long, Footprint> = emptyMap(),
): Long? = write {
    if (!makeRoom(Container.HOME, page, displaced, target = Footprint(x, y, spanX, spanY))) {
        null
    } else {
        dao.insertItem(
            ItemEntity(
                kind = ItemKind.WIDGET,
                container = Container.HOME,
                pageIndex = page,
                x = x,
                y = y,
                spanX = spanX,
                spanY = spanY,
                appWidgetId = appWidgetId,
                provider = provider,
            )
        )
    }
}

/**
 * The widget placed as [id] now covers [to] on its page, after moving aside the neighbours it
 * [displaced]. False, and nothing changed, when it is not a placed widget any more, a displaced
 * neighbour has left the page, or another item on the page is under [to]: the frame was pulled over
 * a picture of the layout that has changed since.
 */
suspend fun WorkspaceRepository.resizeWidget(
    id: Long,
    to: Footprint,
    displaced: Map<Long, Footprint> = emptyMap(),
): Boolean = write {
    val item = dao.item(id)?.takeIf { it.kind == ItemKind.WIDGET }
    val page = item?.pageIndex
    if (page == null || !makeRoom(item.container, page, displaced, mover = id, target = to)) {
        false
    } else {
        dao.updateItem(item.copy(x = to.x, y = to.y, spanX = to.width, spanY = to.height))
        true
    }
}

/**
 * Replaces the host ids of placed widgets according to [remapped] (old id to new id), as a system
 * restore hands them over, and returns the new ids that matched no placement so the caller can
 * release them from the host.
 */
suspend fun WorkspaceRepository.remapWidgets(remapped: Map<Int, Int>): Set<Int> {
    if (remapped.isEmpty()) return emptySet()
    return write {
        val used = mutableSetOf<Int>()
        // Read before any row is written: an old id in one row may be the new id of another, and a
        // query per old id would then remap the first row twice.
        for (item in dao.itemsOfKind(ItemKind.WIDGET)) {
            val newId = item.appWidgetId?.let(remapped::get) ?: continue
            dao.updateItem(item.copy(appWidgetId = newId))
            used += newId
        }
        remapped.values.toSet() - used
    }
}
