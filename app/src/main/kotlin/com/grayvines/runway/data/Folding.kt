package com.grayvines.runway.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** A folder and the apps in it, in order. */
data class FolderContent(val id: Long, val name: String, val apps: List<AppRef>)

/** What is dropped onto an icon to fold with it: a placement, or an app fresh from the drawer. */
sealed interface Dropped {
    data class Item(val id: Long) : Dropped

    data class App(val ref: AppRef) : Dropped
}

internal const val NEW_FOLDER_NAME = "Folder"

fun WorkspaceRepository.observeFolders(): Flow<List<FolderContent>> =
    combine(dao.observeFolders(), dao.observeFolderApps()) { folders, apps ->
        val byFolder = apps.groupBy { it.folderId }
        folders.map { folder ->
            FolderContent(
                folder.id,
                folder.name,
                byFolder[folder.id].orEmpty().map { AppRef(it.component, it.profile) },
            )
        }
    }

/**
 * Drops [dropped] onto the placement [targetId]. An app there becomes a folder of the two; a folder
 * there takes the app in. A dropped placement is gone afterwards (its page is left for the drop to
 * prune). Anything else there, a dropped side that no longer exists, or an app dropped onto itself
 * (which only loses the extra placement) leaves the target as it was. True when the app went in.
 *
 * An app lifted [outOf] a folder leaves it as it goes in, and the folder goes too if that empties
 * it; dropped back onto its own folder, it simply stays.
 */
suspend fun WorkspaceRepository.foldInto(
    targetId: Long,
    dropped: Dropped,
    outOf: Long? = null,
): Boolean = write {
    val target = dao.item(targetId)?.takeIf { it.folderId != null || it.appRef() != null }
    val app = target?.let { dao.take(dropped, notOnto = it) }
    val folderId = if (target != null && app != null) dao.folderOf(target) else null
    if (folderId != null && app != null && folderId != outOf) {
        dao.addToFolder(folderId, app)
        if (outOf != null) dao.leave(outOf, app)
    }
    folderId != null
}

/**
 * Takes [app] out of the folder [folderId] and places it in a cell of its own; the folder goes,
 * with its placement, if that empties it.
 */
suspend fun WorkspaceRepository.unfold(
    folderId: Long,
    app: AppRef,
    container: Container,
    page: Int,
    x: Int,
    y: Int,
) = write {
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

/**
 * The app [dropped] stands for, taking its placement off its page if it had one; null when it is
 * gone, is [notOnto] itself, or is the very app [notOnto] holds (that placement still goes: two of
 * the same icon on a page is what the drop was trying to tidy).
 */
private suspend fun WorkspaceDao.take(dropped: Dropped, notOnto: ItemEntity): AppRef? {
    val app =
        when (dropped) {
            is Dropped.App -> dropped.ref
            is Dropped.Item ->
                item(dropped.id)
                    ?.takeIf { it.id != notOnto.id }
                    ?.let { item -> item.appRef()?.also { deleteItem(item.id) } }
        }
    return app?.takeIf { it != notOnto.appRef() }
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
