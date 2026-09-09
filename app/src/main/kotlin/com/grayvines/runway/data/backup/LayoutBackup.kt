package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.FolderAppEntity
import com.grayvines.runway.data.FolderEntity
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.PageEntity
import com.grayvines.runway.data.WorkspaceDao
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.appRef

/** The layout as it is now, as a backup carries it. */
suspend fun WorkspaceRepository.layoutBackup(): Layout {
    val pages = dao.allPages()
    val folderApps = dao.folderApps().groupBy { it.folderId }
    val folders = dao.folders().associateBy { it.id }
    val items = dao.items()
    // Drawer folders by name, and which index each folder id became.
    val drawerIds =
        items
            .filter { it.container == Container.DRAWER }
            .mapNotNull { it.folderId }
            .filter { it in folders }
            .sortedBy { folders.getValue(it).name }
    val drawerIndex = drawerIds.withIndex().associate { (i, id) -> id to i }
    val placements = items.mapNotNull { it.placement(folders, folderApps, drawerIndex) }
    return Layout(
        homePages = pages.count { it.container == Container.HOME },
        dockPages = pages.count { it.container == Container.DOCK },
        placements =
            placements.sortedWith(compareBy({ it.container }, { it.page }, { it.y }, { it.x })),
        drawerFolders = drawerIds.map { folders.getValue(it).backup(folderApps) },
    )
}

/** What a backup carries for this item: an app or a folder in a cell; nothing for anything else. */
private fun ItemEntity.placement(
    folders: Map<Long, FolderEntity>,
    folderApps: Map<Long, List<FolderAppEntity>>,
    drawerIndex: Map<Long, Int>,
): Placement? {
    val page = pageIndex ?: return null
    val x = x ?: return null
    val y = y ?: return null
    val folder = folderId?.let { folders[it] }
    val inDrawer = folderId?.let { drawerIndex[it] }
    return when {
        kind == ItemKind.APP -> appRef()?.let { Placement(container, page, x, y, app = it) }
        inDrawer != null -> Placement(container, page, x, y, drawerFolder = inDrawer)
        folder != null -> Placement(container, page, x, y, folder = folder.backup(folderApps))
        else -> null // widgets and shortcuts stay with the device they were made on
    }
}

private fun FolderEntity.backup(folderApps: Map<Long, List<FolderAppEntity>>) =
    Folder(name, folderApps[id].orEmpty().sortedBy { it.position }.map { it.ref })

/**
 * Replaces the whole layout with [layout], in one transaction. Apps are matched against [installed]
 * by component and profile, or by component alone where that app is installed in exactly one
 * profile (a new phone numbers its profiles differently); anything else is skipped, and a folder
 * none of whose apps are installed goes with them.
 */
suspend fun WorkspaceRepository.restoreLayout(layout: Layout, installed: Set<AppRef>): Restored =
    write {
        val match = AppMatcher(installed)
        dao.deleteAllItems()
        dao.deleteAllFolders()
        dao.deleteAllPages()
        repeat(maxOf(layout.homePages, 1)) { dao.insertPage(PageEntity(Container.HOME, it)) }
        repeat(maxOf(layout.dockPages, 1)) { dao.insertPage(PageEntity(Container.DOCK, it)) }
        val counts = Counts()
        // Drawer folders first: a placement may refer to one by index.
        val drawerIds = dao.restoreDrawerFolders(layout.drawerFolders, match, counts)
        var placed = counts.placed
        var skipped = counts.skipped
        for (placement in layout.placements) {
            val folder = placement.folder
            val app = placement.app?.let(match::find)
            val drawerFolder = placement.drawerFolder?.let { drawerIds[it] }
            when {
                app != null -> {
                    dao.insertItem(placement.entity(ItemKind.APP, app = app))
                    placed++
                }
                drawerFolder != null -> {
                    dao.insertItem(placement.entity(ItemKind.FOLDER, folderId = drawerFolder))
                    placed++
                }
                folder != null -> {
                    val apps = folder.apps.mapNotNull(match::find)
                    skipped += folder.apps.size - apps.size
                    if (apps.isEmpty()) continue
                    val folderId = dao.insertFolder(FolderEntity(name = folder.name))
                    apps.forEachIndexed { i, ref ->
                        dao.insertFolderApp(
                            FolderAppEntity(folderId, ref.component, ref.profile, i)
                        )
                    }
                    dao.insertItem(placement.entity(ItemKind.FOLDER, folderId = folderId))
                    placed++
                }
                else -> {
                    skipped++
                }
            }
        }
        Restored(placed, skipped)
    }

private fun Placement.entity(kind: ItemKind, app: AppRef? = null, folderId: Long? = null) =
    ItemEntity(
        kind = kind,
        container = container,
        pageIndex = page,
        x = x,
        y = y,
        component = app?.component,
        profile = app?.profile,
        folderId = folderId,
    )

/** Finds the installed app a backed-up reference means, or null. */
private class AppMatcher(private val installed: Set<AppRef>) {
    private val byComponent = installed.groupBy { it.component }

    fun find(ref: AppRef): AppRef? =
        if (ref in installed) ref else byComponent[ref.component]?.singleOrNull()
}

/** Running totals of a restore. */
private class Counts(var placed: Int = 0, var skipped: Int = 0)

/** Makes the drawer folders whose apps are installed; each index maps to the folder id it got. */
private suspend fun WorkspaceDao.restoreDrawerFolders(
    folders: List<Folder>,
    match: AppMatcher,
    counts: Counts,
): Map<Int, Long> {
    val ids = mutableMapOf<Int, Long>()
    folders.forEachIndexed { index, folder ->
        val apps = folder.apps.mapNotNull(match::find)
        counts.skipped += folder.apps.size - apps.size
        if (apps.isNotEmpty()) {
            val folderId = insertFolder(FolderEntity(name = folder.name))
            apps.forEachIndexed { i, ref ->
                insertFolderApp(FolderAppEntity(folderId, ref.component, ref.profile, i))
            }
            insertItem(
                ItemEntity(
                    kind = ItemKind.FOLDER,
                    container = Container.DRAWER,
                    folderId = folderId,
                )
            )
            ids[index] = folderId
            counts.placed++
        }
    }
    return ids
}
