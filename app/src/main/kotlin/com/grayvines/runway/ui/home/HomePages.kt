package com.grayvines.runway.ui.home

import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.FolderContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.appRef
import com.grayvines.runway.data.folderIdentity
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.Placed
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.LabelOrder

/**
 * The pages as the UI draws them. An item whose app is not in [apps] (a profile that is off, or the
 * list not loaded yet) is not drawn, but its cell stays occupied: nothing may be dropped on top of
 * it.
 */
internal fun ContainerContent.toHomePages(
    apps: Map<String, AppEntry>,
    folders: Map<Long, FolderContent> = emptyMap(),
): List<HomePage> = pages.map { page ->
    val items = page.items.mapNotNull { it.toHomeItem(apps, folders) }
    val drawn = items.mapTo(mutableSetOf()) { it.id }
    HomePage(
        index = page.index,
        items = items,
        occupied = page.items.mapNotNull { it.placed(drawn = it.id in drawn) },
    )
}

/** [drawn] apps take a dropped app in; one whose profile is off is a cell to keep clear of. */
private fun ItemEntity.placed(drawn: Boolean): Placed? {
    val cellX = x ?: return null
    val cellY = y ?: return null
    return Placed(
        id,
        Footprint(cellX, cellY, spanX, spanY),
        foldable = kind == ItemKind.FOLDER || kind == ItemKind.APP && drawn,
        identity = appRef()?.identity ?: folderId?.let(::folderIdentity),
    )
}

/** The drawer's folders as tiles, by name; a placement whose folder is gone is nothing. */
internal fun List<ItemEntity>.toDrawerFolders(
    apps: Map<String, AppEntry>,
    folders: Map<Long, FolderContent>,
): List<HomeItem> = mapNotNull { item ->
    item.folderId?.let { folders[it] }?.tile(item.id, apps)
}
    .sortedWith(compareBy(LabelOrder.comparator()) { it.label })

private fun FolderContent.tile(placementId: Long, apps: Map<String, AppEntry>) =
    HomeItem(
        id = placementId,
        kind = ItemKind.FOLDER,
        x = 0,
        y = 0,
        spanX = 1,
        spanY = 1,
        label = name,
        app = null,
        folder = this.apps.mapNotNull { apps["${it.profile}/${it.component}"] },
        folderId = id,
        inDrawer = true,
    )

/** Null when the item has no cell or its app is absent; those are not drawn. */
private fun ItemEntity.toHomeItem(
    apps: Map<String, AppEntry>,
    folders: Map<Long, FolderContent>,
): HomeItem? {
    val cellX = x ?: return null
    val cellY = y ?: return null
    val app = apps["$profile/$component"]
    if (kind == ItemKind.APP && app == null) return null
    val folder = folderId?.let { folders[it] }
    return HomeItem(
        id = id,
        kind = kind,
        x = cellX,
        y = cellY,
        spanX = spanX,
        spanY = spanY,
        label = labelOverride ?: app?.label ?: folder?.name ?: "",
        app = app,
        folder = folder?.apps?.mapNotNull { apps["${it.profile}/${it.component}"] }.orEmpty(),
        folderId = folder?.id,
        inDrawer = folder?.inDrawer == true,
    )
}
