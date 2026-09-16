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
