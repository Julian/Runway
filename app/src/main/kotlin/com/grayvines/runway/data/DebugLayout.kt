package com.grayvines.runway.data

/*
 * Debug-only ways to reset a layout: settings' "Fill with all apps" and "Clear layout", and the
 * on-device tests' seeding. Kept apart from the repository proper.
 */

suspend fun WorkspaceRepository.clear() = write {
    dao.deleteAllItems()
    dao.deleteAllPages()
    dao.insertPage(PageEntity(Container.HOME, 0))
    dao.insertPage(PageEntity(Container.DOCK, 0))
}

/** Debug helper: fills the dock, then home pages row by row, with [apps] in order. */
suspend fun WorkspaceRepository.autoFill(
    apps: List<AppRef>,
    columns: Int,
    pageRows: Int,
    dockSlots: Int,
) = write {
    dao.deleteAllItems()
    dao.deleteAllPages()
    dao.insertPage(PageEntity(Container.DOCK, 0))
    apps.take(dockSlots).forEachIndexed { slot, app ->
        dao.insertItem(app.placement(Container.DOCK, page = 0, x = slot, y = 0))
    }
    val perPage = columns * pageRows
    apps.drop(dockSlots).chunked(perPage).forEachIndexed { page, pageApps ->
        dao.insertPage(PageEntity(Container.HOME, page))
        pageApps.forEachIndexed { i, app ->
            dao.insertItem(app.placement(Container.HOME, page, x = i % columns, y = i / columns))
        }
    }
    dao.insertPage(PageEntity(Container.HOME, 0))
}

private fun AppRef.placement(container: Container, page: Int, x: Int, y: Int) =
    ItemEntity(
        kind = ItemKind.APP,
        container = container,
        pageIndex = page,
        x = x,
        y = y,
        component = component,
        profile = profile,
    )
