package com.grayvines.runway.data

import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.overlaps

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
    appWidgetId: Int,
    provider: String,
    page: Int,
    x: Int,
    y: Int,
    spanX: Int,
    spanY: Int,
    displaced: Map<Long, Footprint> = emptyMap(),
): Long? = write {
    if (!makeRoom(Container.HOME, page, displaced)) {
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
 * The widget placed as [id] now covers [to] on its page. False, and nothing changed, when it is not
 * a placed widget any more or another item on the page is under [to]: the frame was pulled over a
 * picture of the layout that has changed since.
 */
suspend fun WorkspaceRepository.resizeWidget(id: Long, to: Footprint): Boolean = write {
    val item = dao.item(id)
    val page = item?.pageIndex
    if (item == null || item.kind != ItemKind.WIDGET || page == null) {
        false
    } else {
        val taken =
            dao.items().any { other ->
                other.id != id &&
                    other.container == item.container &&
                    other.pageIndex == page &&
                    other.footprint()?.overlaps(to) == true
            }
        if (!taken) {
            dao.updateItem(item.copy(x = to.x, y = to.y, spanX = to.width, spanY = to.height))
        }
        !taken
    }
}

private fun ItemEntity.footprint(): Footprint? {
    val cellX = x ?: return null
    val cellY = y ?: return null
    return Footprint(cellX, cellY, spanX, spanY)
}
