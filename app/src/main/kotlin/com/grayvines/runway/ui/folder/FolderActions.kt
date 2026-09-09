package com.grayvines.runway.ui.folder

import com.grayvines.runway.system.apps.AppEntry

/** What an open folder's sheet can ask for. */
class FolderActions(
    val launch: (AppEntry) -> Unit,
    val close: () -> Unit,
    /** Names the folder of this id, whether or not it is still the open one. */
    val rename: (folderId: Long, name: String) -> Unit,
)
