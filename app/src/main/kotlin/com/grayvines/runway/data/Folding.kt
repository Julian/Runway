package com.grayvines.runway.data

import com.grayvines.runway.model.Footprint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A folder and the apps in it, in order; [inDrawer] when one of its placements is the drawer. */
data class FolderContent(
    val id: Long,
    val name: String,
    val apps: List<AppRef>,
    val inDrawer: Boolean = false,
)

/** What is dropped onto an icon to fold with it: a placement, or an app fresh from the drawer. */
sealed interface Dropped {
    data class Item(val id: Long) : Dropped

    data class App(val ref: AppRef) : Dropped
}

internal const val NEW_FOLDER_NAME = "Folder"

fun WorkspaceRepository.observeFolders(): Flow<List<FolderContent>> =
    combine(dao.observeFolders(), dao.observeFolderApps(), dao.observeItems(Container.DRAWER)) {
        folders,
        apps,
        drawer ->
        val byFolder = apps.groupBy { it.folderId }
        val inDrawer = drawer.mapNotNullTo(HashSet()) { it.folderId }
        folders.map { folder ->
            FolderContent(
                folder.id,
                folder.name,
                byFolder[folder.id].orEmpty().map { AppRef(it.component, it.profile) },
                inDrawer = folder.id in inDrawer,
            )
        }
    }

/**
 * Drops [dropped] onto the placement [targetId]. An app there becomes a folder of the two; a folder
 * there takes the app in, and a folder placed in the drawer takes it out of any other drawer folder
 * first. A dropped placement is gone afterwards (its page is left for the drop to prune). Anything
 * else there, a dropped side that no longer exists, or an app dropped onto another placement of
 * itself leaves everything as it was, the dropped placement included. True when the app went in.
 *
 * An app lifted [outOf] a folder leaves it as it goes in, and the folder goes too if that empties
 * it. Dropped back onto its own folder, it simply stays (true); a placement of it picked up on the
 * way is put back where it was (false: nothing changed).
 */
suspend fun WorkspaceRepository.foldInto(
    targetId: Long,
    dropped: Dropped,
    outOf: Long? = null,
): Boolean = write { dao.foldOnto(targetId, dropped, outOf) }

private suspend fun WorkspaceDao.foldOnto(targetId: Long, dropped: Dropped, outOf: Long?): Boolean {
    val target =
        item(targetId)?.takeIf { it.kind == ItemKind.FOLDER || it.kind == ItemKind.APP }
            ?: return false
    val taken = take(dropped, notOnto = target) ?: return false
    val folderId = folderOf(target) ?: return false
    if (folderId == outOf) return taken.placementId == null
    fold(folderId, taken, outOf)
    return true
}

/** [taken] goes into [folderId], off its page and out of [outOf] and any other drawer folder. */
private suspend fun WorkspaceDao.fold(folderId: Long, taken: Taken, outOf: Long?) {
    taken.placementId?.let { deleteItem(it) }
    if (isPlacedIn(folderId, Container.DRAWER)) {
        leaveDrawerFolders(taken.app.component, taken.app.profile)
        deleteEmptyFolders()
    }
    addToFolder(folderId, taken.app)
    if (outOf != null) leave(outOf, taken.app)
}

/**
 * Takes [app] out of the folder [folderId] and places it in a cell of its own, after moving aside
 * the neighbours it [displaced]; the folder goes, with its placement, if that empties it. False,
 * and nothing changed, if the neighbours could not be moved.
 */
@Suppress("LongParameterList") // one drop, spelled out
suspend fun WorkspaceRepository.unfold(
    folderId: Long,
    app: AppRef,
    container: Container,
    page: Int,
    x: Int,
    y: Int,
    displaced: Map<Long, Footprint> = emptyMap(),
): Boolean = write {
    if (!makeRoom(container, page, displaced)) {
        false
    } else {
        dao.insertItem(
            ItemEntity(
                kind = ItemKind.APP,
                container = container,
                pageIndex = page,
                x = x,
                y = y,
                component = app.component,
                profile = app.profile,
            )
        )
        dao.leave(folderId, app)
        true
    }
}

/**
 * Moves the placement [itemId] as [WorkspaceRepository.moveItem] does and, in the same transaction,
 * takes its app out of the folder [folderId]: what dragging an app out of a folder onto a page that
 * already holds it comes to, once that placement has been picked up in the folder app's stead. The
 * folder goes if that empties it. False, and nothing changed, if the move could not be made.
 */
@Suppress("LongParameterList") // one drop, spelled out
suspend fun WorkspaceRepository.moveOutOf(
    folderId: Long,
    itemId: Long,
    container: Container,
    page: Int,
    x: Int,
    y: Int,
    displaced: Map<Long, Footprint> = emptyMap(),
): Boolean = write {
    val app = dao.item(itemId)?.appRef()
    if (app != null && relocate(itemId, container, page, x, y, displaced)) {
        dao.leave(folderId, app)
        true
    } else {
        false
    }
}

/** [app] leaves [folderId]; a folder left empty is gone, with every placement of it. */
private suspend fun WorkspaceDao.leave(folderId: Long, app: AppRef) {
    removeFromFolder(folderId, app.component, app.profile)
    deleteEmptyFolders()
}

/** Gives the folder a new name, trimmed; a blank one is no name at all and changes nothing. */
suspend fun WorkspaceRepository.renameFolder(folderId: Long, name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    write { dao.renameFolder(folderId, trimmed) }
    return true
}

/** The folder [item] is, or becomes (an app turns into a new folder holding it); else null. */
private suspend fun WorkspaceDao.folderOf(item: ItemEntity): Long? =
    when (item.kind) {
        ItemKind.FOLDER -> item.folderId
        ItemKind.APP -> foldApp(item)
        else -> null
    }

private suspend fun WorkspaceDao.foldApp(item: ItemEntity): Long {
    val folderId = insertFolder(FolderEntity(name = NEW_FOLDER_NAME))
    item.appRef()?.let { addToFolder(folderId, it) }
    updateItem(
        item.copy(kind = ItemKind.FOLDER, folderId = folderId, component = null, profile = null)
    )
    return folderId
}

/** What a drop stands for: the app, and the placement it came off the page as, if any. */
private class Taken(val app: AppRef, val placementId: Long?)

/**
 * The app [dropped] stands for, with its placement if it had one (nothing is deleted here; the fold
 * decides that); null when it is gone, is [notOnto] itself, or is the very app [notOnto] holds:
 * there is nothing to fold two of the same into.
 */
private suspend fun WorkspaceDao.take(dropped: Dropped, notOnto: ItemEntity): Taken? {
    val placement = (dropped as? Dropped.Item)?.let { item(it.id) }?.takeIf { it.id != notOnto.id }
    val app =
        when (dropped) {
            is Dropped.App -> dropped.ref
            is Dropped.Item -> placement?.appRef()
        }
    if (app == null || app == notOnto.appRef()) return null
    return Taken(app, placement?.id)
}

internal suspend fun WorkspaceDao.addToFolder(folderId: Long, app: AppRef) {
    val position = nextFolderPosition(folderId)
    insertFolderApp(FolderAppEntity(folderId, app.component, app.profile, position))
}

internal fun ItemEntity.appRef(): AppRef? {
    val component = component ?: return null
    val profile = profile ?: return null
    return AppRef(component, profile)
}
