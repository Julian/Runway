package com.grayvines.runway.data

/* Edits to a folder that are not drops: made from its open sheet and the menus over it. */

/** Gives the folder a new name, trimmed; a blank one is no name at all and changes nothing. */
suspend fun WorkspaceRepository.renameFolder(folderId: Long, name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    write { dao.renameFolder(folderId, trimmed) }
    return true
}

/**
 * Takes [app] out of the folder [folderId] without placing it anywhere. A folder this empties
 * stays, placements and all, so the sheet it was emptied in can still take apps;
 * [pruneEmptyFolders] takes it once that sheet has closed.
 */
suspend fun WorkspaceRepository.removeFromFolder(folderId: Long, app: AppRef) = write {
    dao.removeFromFolder(folderId, app.component, app.profile)
}

/** Deletes every folder with no apps in it, and (by cascade) every placement of each. */
suspend fun WorkspaceRepository.pruneEmptyFolders() = write { dao.deleteEmptyFolders() }

/**
 * Puts the folder's apps in [order], the order its sheet was left showing, and keeps them that way:
 * from then on the folder holds a hand order rather than the drawer's, and apps that join it later
 * go last. An app that left the folder meanwhile is not in it to place, and one that joined it
 * meanwhile follows [order], as a newcomer does.
 */
suspend fun WorkspaceRepository.reorderFolder(folderId: Long, order: List<AppRef>) = write {
    val held = dao.folderApps(folderId).map { it.ref }
    val placed = order.filter { it in held }
    (placed + held.filterNot { it in placed }).forEachIndexed { position, app ->
        dao.setFolderPosition(folderId, app.component, app.profile, position)
    }
}
