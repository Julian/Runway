package com.grayvines.runway.ui.menu

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.Container
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.home.AppIcon
import com.grayvines.runway.ui.home.HomeItem

const val ITEM_MENU_TAG = "item-menu"

private val HEADER_ICON = 28.dp
private const val DIVIDER_ALPHA = 0.12f

/** The item a long press landed on, where its cell is (root px), and where it lives. */
data class ItemMenuState(
    val item: HomeItem,
    val container: Container,
    val page: Int,
    val anchor: Bounds,
)

/** The item menu as the screen sees it: what it is open on, if anything, and how to drive it. */
class ItemMenuSession(
    val at: ItemMenuState?,
    val actions: ItemMenuActions,
    val onDismiss: () -> Unit,
)

/** What the menu can do to its item. */
class ItemMenuActions(
    val appInfo: () -> Unit,
    val uninstall: () -> Unit,
    val remove: () -> Unit,
    /** A drawer app: a new drawer folder holding it. */
    val newFolder: () -> Unit,
    /** A drawer app: into the drawer folder of this id. */
    val addToFolder: (folderId: Long) -> Unit,
    /** A drawer folder: gone, its apps loose again. */
    val deleteFolder: () -> Unit,
)

/**
 * The menu beside a long-pressed item, in root coordinates. What it offers depends on where the
 * item lives; [drawerFolders] are what a drawer app can be added to.
 */
@Composable
fun ItemMenu(
    state: ItemMenuState,
    actions: ItemMenuActions,
    drawerFolders: List<HomeItem>,
    onDismiss: () -> Unit,
) {
    Menu(state.anchor, ITEM_MENU_TAG, onDismiss) { Rows(state, actions, drawerFolders) }
}

/** The rows for this item: an app's, a drawer folder's, or a home or dock placement's. */
@Composable
private fun Rows(state: ItemMenuState, actions: ItemMenuActions, drawerFolders: List<HomeItem>) {
    val app = state.item.app
    val inDrawer = state.container == Container.DRAWER
    if (app != null) {
        Header(app, state.item.label)
        HorizontalDivider(color = Color.White.copy(alpha = DIVIDER_ALPHA))
    }
    when {
        inDrawer && app != null -> {
            MenuRow("New folder", Icons.Outlined.CreateNewFolder, actions.newFolder)
            // Every drawer folder but the one the app is already in, if any.
            val targets =
                drawerFolders
                    .filter { f -> f.folder.none { it.key == app.key } }
                    .mapNotNull { f -> f.folderId?.let { f.label to it } }
            targets.forEach { (label, id) ->
                MenuRow("Add to $label", Icons.Outlined.Folder) {
                    actions.addToFolder(id)
                }
            }
            MenuRow("App info", Icons.Outlined.Info, actions.appInfo)
            MenuRow("Uninstall", Icons.Outlined.Delete, actions.uninstall)
        }
        inDrawer -> {
            MenuRow("Delete folder", Icons.Outlined.Delete, actions.deleteFolder)
        }
        else -> {
            if (app != null) {
                MenuRow("App info", Icons.Outlined.Info, actions.appInfo)
                MenuRow("Uninstall", Icons.Outlined.Delete, actions.uninstall)
            }
            MenuRow("Remove", Icons.Outlined.Clear, actions.remove)
        }
    }
}

@Composable
private fun Header(app: AppEntry, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AppIcon(app, Modifier.size(HEADER_ICON))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
