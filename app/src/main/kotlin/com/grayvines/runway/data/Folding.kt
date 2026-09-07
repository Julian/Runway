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

private const val NEW_FOLDER_NAME = "Folder"

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
 * there takes the app in. A dropped placement is gone afterwards, its page pruned if that left it
 * empty. Anything else there is left alone.
 */
suspend fun WorkspaceRepository.foldInto(targetId: Long, dropped: Dropped) {
    write {
        val folderId = dao.item(targetId)?.let { dao.folderOf(it) }
        val app = folderId?.let { dao.take(dropped) }
        if (folderId != null && app != null) dao.addToFolder(folderId, app)
    }
    pruneTrailingEmptyPages(Container.HOME)
    pruneTrailingEmptyPages(Container.DOCK)
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

/** The app [dropped] stands for, taking its placement off its page if it had one. */
private suspend fun WorkspaceDao.take(dropped: Dropped): AppRef? =
    when (dropped) {
        is Dropped.App -> dropped.ref
        is Dropped.Item ->
            item(dropped.id)?.let { item -> item.appRef()?.also { deleteItem(item.id) } }
    }

private suspend fun WorkspaceDao.addToFolder(folderId: Long, app: AppRef) {
    val position = nextFolderPosition(folderId)
    insertFolderApp(FolderAppEntity(folderId, app.component, app.profile, position))
}

private fun ItemEntity.appRef(): AppRef? {
    val component = component ?: return null
    val profile = profile ?: return null
    return AppRef(component, profile)
}
