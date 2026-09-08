package com.grayvines.runway.ui.folder

import com.grayvines.runway.system.apps.AppEntry

/** What an open folder's sheet can ask for. */
class FolderActions(
    val launch: (AppEntry) -> Unit,
    val close: () -> Unit,
    val rename: (String) -> Unit,
)
