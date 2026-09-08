package com.grayvines.runway.data

import kotlinx.coroutines.flow.Flow

/*
 * Drawer folders: folders placed in the drawer. Made and filled from an app's menu, never by a
 * drop; an app is in at most one of them, and the drawer's grid leaves out the apps they hold.
 */

/**
 * The drawer's placements: one per drawer folder, with no cell. A drawer folder is simply a folder
 * placed in the drawer; its apps stay out of the drawer's alphabetical grid.
 */
fun WorkspaceRepository.observeDrawerPlacements(): Flow<List<ItemEntity>> =
    dao.observeItems(Container.DRAWER)

/** A new drawer folder holding [app], which leaves any other drawer folder it was in. */
suspend fun WorkspaceRepository.createDrawerFolder(app: AppRef): Long = write {
    val folderId = dao.insertFolder(FolderEntity(name = NEW_FOLDER_NAME))
    dao.insertItem(
        ItemEntity(kind = ItemKind.FOLDER, container = Container.DRAWER, folderId = folderId)
    )
    dao.moveIntoDrawerFolder(folderId, app)
    folderId
}

/** Puts [app] in the drawer folder [folderId], out of any other; one emptied by that goes. */
suspend fun WorkspaceRepository.addToDrawerFolder(folderId: Long, app: AppRef) = write {
    dao.moveIntoDrawerFolder(folderId, app)
    dao.deleteEmptyFolders()
}

/** Deletes the folder outright: every placement of it goes, and its apps are loose again. */
suspend fun WorkspaceRepository.deleteFolder(folderId: Long) = write { dao.deleteFolder(folderId) }

private suspend fun WorkspaceDao.moveIntoDrawerFolder(folderId: Long, app: AppRef) {
    leaveDrawerFolders(app.component, app.profile)
    addToFolder(folderId, app)
}
