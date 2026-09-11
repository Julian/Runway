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
