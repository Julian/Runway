package com.grayvines.runway.ui.home

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.FolderContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.appRef
import com.grayvines.runway.data.folderIdentity
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.Placed
import com.grayvines.runway.model.overlaps
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.LabelOrder
import com.grayvines.runway.ui.drag.DropAreas
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.homeCellAt

/**
 * The pages as the UI draws them, within [columns] × [rows]. An item whose app is not in [apps] (a
 * profile that is off, or the list not loaded yet) or whose cells fall outside the grid is not
 * drawn, but its cell stays occupied: nothing may be dropped on top of it.
 */
internal fun ContainerContent.toHomePages(
    apps: Map<AppRef, AppEntry>,
    folders: Map<Long, FolderContent> = emptyMap(),
    columns: Int = Int.MAX_VALUE,
    rows: Int = Int.MAX_VALUE,
): List<HomePage> {
    // One collator for the whole layout: making one costs more than the sorts do.
    val byEntry = LabelOrder.entryComparator()
    val appLabels =
        apps.entries.associate { (ref, app) -> ref.component.substringBefore('/') to app.label }
    return pages.map { page ->
        val items =
            page.items
                .mapNotNull { it.toHomeItem(apps, appLabels, folders, byEntry) }
                .filter {
                    it.x >= 0 && it.y >= 0 && it.x + it.spanX <= columns && it.y + it.spanY <= rows
                }
        val drawn = items.mapTo(mutableSetOf()) { it.id }
        HomePage(
            index = page.index,
            items = items,
            occupied = page.items.mapNotNull { it.placed(drawn = it.id in drawn) },
        )
    }
}

/** Whether the root point [p] lies on a widget of the home page shown in [areas]. */
internal fun HomeState.hasWidgetAt(areas: DropAreas, p: Point): Boolean {
    val cell = areas.homeCellAt(p) ?: return false
    return homePages
        .firstOrNull { it.index == cell.page }
        ?.items
        .orEmpty()
        .any { it.kind == ItemKind.WIDGET && it.footprint.overlaps(Footprint(cell.x, cell.y)) }
}

/**
 * [drawn] apps and folders take a dropped app in; one whose profile is off or that lies outside the
 * grid is a cell to keep clear of.
 */
private fun ItemEntity.placed(drawn: Boolean): Placed? {
    val cellX = x ?: return null
    val cellY = y ?: return null
    return Placed(
        id,
        Footprint(cellX, cellY, spanX, spanY),
        foldable = (kind == ItemKind.FOLDER || kind == ItemKind.APP) && drawn,
        identity = appRef()?.identity ?: folderId?.let(::folderIdentity),
    )
}

/** The drawer's folders as tiles, by name; a placement whose folder is gone is nothing. */
internal fun List<ItemEntity>.toDrawerFolders(
    apps: Map<AppRef, AppEntry>,
    folders: Map<Long, FolderContent>,
): List<HomeItem> {
    val byLabel = LabelOrder.comparator()
    val byEntry = LabelOrder.entryComparator()
    return mapNotNull { item -> item.folderId?.let { folders[it] }?.tile(item.id, apps, byEntry) }
        .sortedWith(compareBy(byLabel) { it.label })
}

private fun FolderContent.tile(
    placementId: Long,
    apps: Map<AppRef, AppEntry>,
    byEntry: Comparator<AppEntry>,
) =
    HomeItem(
        id = placementId,
        kind = ItemKind.FOLDER,
        x = 0,
        y = 0,
        spanX = 1,
        spanY = 1,
        label = name,
        app = null,
        folder = held(apps, byEntry),
        folderId = id,
        inDrawer = true,
    )

/**
 * The folder's apps as it shows them: in its hand order once one has been dragged into place,
 * otherwise as the drawer lists them. One whose app is absent (a profile that is off, the list not
 * loaded yet) is not among them.
 */
private fun FolderContent.held(
    apps: Map<AppRef, AppEntry>,
    byEntry: Comparator<AppEntry>,
): List<AppEntry> {
    val entries = this.apps.mapNotNull(apps::get)
    return if (handSorted) entries else entries.sortedWith(byEntry)
}

/** Null when the item has no cell or its app is absent; those are not drawn. */
private fun ItemEntity.toHomeItem(
    apps: Map<AppRef, AppEntry>,
    appLabels: Map<String, String>,
    folders: Map<Long, FolderContent>,
    byEntry: Comparator<AppEntry>,
): HomeItem? {
    val cellX = x ?: return null
    val cellY = y ?: return null
    val app = appRef()?.let(apps::get)
    if (kind == ItemKind.APP && app == null) return null
    val folder = folderId?.let { folders[it] }
    return HomeItem(
        id = id,
        kind = kind,
        x = cellX,
        y = cellY,
        spanX = spanX,
        spanY = spanY,
        label =
            labelOverride
                ?: app?.label
                ?: folder?.name
                ?: provider?.let { widgetLabel(it, appLabels) }
                ?: "",
        app = app,
        folder = folder?.held(apps, byEntry).orEmpty(),
        folderId = folder?.id,
        inDrawer = folder?.inDrawer == true,
        appWidgetId = appWidgetId,
        provider = provider,
    )
}

/**
 * The name of the app that owns a widget's [provider] (a flattened component name), from the
 * installed apps' [appLabels] (by package name), or the package name itself once the app is gone.
 */
internal fun widgetLabel(provider: String, appLabels: Map<String, String>): String {
    val pkg = provider.substringBefore('/')
    return appLabels[pkg] ?: pkg
}
