package com.grayvines.runway.ui.folder

import com.grayvines.runway.system.apps.AppEntry

/** What an open folder's sheet can ask for. */
class FolderActions(
    val launch: (AppEntry) -> Unit,
    val close: () -> Unit,
    /** Names the folder of this id, whether or not it is still the open one. */
    val rename: (folderId: Long, name: String) -> Unit,
    /** Puts the app in the drawer folder of this id, taking it out of any other drawer folder. */
    val add: (folderId: Long, app: AppEntry) -> Unit,
    /** Takes the app out of the folder of this id; a folder that empties stays until it closes. */
    val remove: (folderId: Long, app: AppEntry) -> Unit,
)
