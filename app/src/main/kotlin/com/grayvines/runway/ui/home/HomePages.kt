package com.grayvines.runway.ui.home

import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.FolderContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.Placed
import com.grayvines.runway.system.apps.AppEntry

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
    )
}

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
    )
}
