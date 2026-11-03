package com.grayvines.runway.ui.folder

import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds

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
    /** The order a finger left the folder's apps in, which the folder keeps from then on. */
    val reorder: (folderId: Long, apps: List<AppEntry>) -> Unit,
    /** A hold on an app in the folder of this id, in [cell] (root px): opens its menu. */
    val hold: (folderId: Long, app: AppEntry, cell: Bounds) -> Unit = { _, _, _ -> },
    /** Closes the menu a hold in the folder opened, once the finger moves on. */
    val dismissMenu: () -> Unit = {},
)
