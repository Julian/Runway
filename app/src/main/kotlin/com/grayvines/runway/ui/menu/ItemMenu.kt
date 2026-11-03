package com.grayvines.runway.ui.menu

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.Container
import com.grayvines.runway.system.apps.AppShortcut
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.folder.REMOVE_SIZE
import com.grayvines.runway.ui.folder.REMOVE_TOUCH
import com.grayvines.runway.ui.home.HomeItem
import kotlin.math.roundToInt

const val ITEM_MENU_TAG = "item-menu"

private const val DIVIDER_ALPHA = 0.12f
private val REMOVE_CHIP = Color(0xFFD93025)
private val REMOVE_ICON = 18.dp
private val CHIP_INSET = 4.dp

/**
 * The item a long press landed on, where its cell is (root px), and where it lives. An app with no
 * placement of its own lives in the drawer, whether held in the drawer's grid or in an open folder;
 * held in a folder, [folderId] is that folder.
 */
data class ItemMenuState(
    val item: HomeItem,
    val container: Container,
    val page: Int,
    val anchor: Bounds,
    val folderId: Long? = null,
    val badgesMuted: Boolean = false,
    val shortcuts: List<AppShortcut> = emptyList(),
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
    /** A drawer folder: gone, its apps loose again. */
    val deleteFolder: () -> Unit,
    /** Toggles whether notification badges are shown for this app. */
    val toggleBadges: () -> Unit = {},
    /** Launches one of the app's static or dynamic shortcuts. */
    val startShortcut: (AppShortcut) -> Unit = {},
)

/**
 * The menu beside a long-pressed item, in root coordinates. A placed item on the home screen or the
 * dock gets a red remove chip on its top-right corner; an app or drawer folder also gets the card
 * below or above its cell.
 */
@Composable
fun ItemMenu(
    state: ItemMenuState,
    actions: ItemMenuActions,
    iconSize: Dp,
    onDismiss: () -> Unit,
    labelled: Boolean = true,
) {
    val inDrawer = state.container == Container.DRAWER
    val hasCard = state.item.app != null || inDrawer
    val cardContent: (@Composable ColumnScope.() -> Unit)? =
        if (hasCard) {
            { Rows(state, actions) }
        } else {
            null
        }
    Menu(
        anchor = state.anchor,
        tag = ITEM_MENU_TAG,
        onDismiss = onDismiss,
        overlay = { room ->
            if (!inDrawer) {
                HeldRemoveChip(state.anchor, iconSize, labelled, room, actions.remove)
            }
        },
        content = cardContent,
    )
}

/**
 * The card content for this item: an app's shortcuts (if any) over its icon-button bar, or a drawer
 * folder's delete row.
 */
@Composable
private fun Rows(state: ItemMenuState, actions: ItemMenuActions) {
    val app = state.item.app
    val inDrawer = state.container == Container.DRAWER
    if (app != null) {
        if (state.shortcuts.isNotEmpty()) {
            state.shortcuts.forEach { shortcut ->
                ShortcutRow(shortcut) { actions.startShortcut(shortcut) }
            }
            HorizontalDivider(
                color = Color.White.copy(alpha = DIVIDER_ALPHA),
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
        AppActionBar(
            showNewFolder = inDrawer && state.folderId == null,
            badgesMuted = state.badgesMuted,
            actions = actions,
        )
    } else if (inDrawer) {
        MenuRow("Delete folder", Icons.Outlined.Delete, actions.deleteFolder)
    }
}

/** The compact row of icon buttons for an app: new folder (in the drawer), info, badges, delete. */
@Composable
private fun AppActionBar(
    showNewFolder: Boolean,
    badgesMuted: Boolean,
    actions: ItemMenuActions,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showNewFolder) {
            MenuIconButton("New folder", Icons.Outlined.CreateNewFolder, actions.newFolder)
        }
        MenuIconButton("App info", Icons.Outlined.Info, actions.appInfo)
        MenuIconButton(
            if (badgesMuted) "Unmute badges" else "Mute badges",
            if (badgesMuted) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff,
            actions.toggleBadges,
        )
        MenuIconButton("Uninstall", Icons.Outlined.Delete, actions.uninstall)
    }
}

@Composable
private fun ShortcutRow(shortcut: AppShortcut, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        val bitmap = shortcut.icon
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(ROW_ICON),
            )
        } else {
            Icon(
                Icons.AutoMirrored.Outlined.Launch,
                contentDescription = null,
                tint = Color.White.copy(alpha = ICON_ALPHA),
                modifier = Modifier.size(ROW_ICON),
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = shortcut.label,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The red x on the top-right corner of a held home or dock placement. */
@Composable
private fun HeldRemoveChip(
    anchor: Bounds,
    iconSize: Dp,
    labelled: Boolean,
    room: IntSize,
    onRemove: () -> Unit,
) {
    val density = LocalDensity.current
    val iconPx = with(density) { iconSize.roundToPx() }
    val touchPx = with(density) { REMOVE_TOUCH.roundToPx() }
    val insetPx = with(density) { CHIP_INSET.roundToPx() }
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelPx = if (labelled) with(density) { labelStyle.lineHeight.roundToPx() } else 0
    Box(
        modifier =
            Modifier.offset { removeChipOffset(anchor, iconPx, touchPx, insetPx, room, labelPx) }
                .size(REMOVE_TOUCH)
                .clickable(role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = "Remove" },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(REMOVE_SIZE).background(REMOVE_CHIP, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Clear,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(REMOVE_ICON),
            )
        }
    }
}

/**
 * Top-left of the [touchPx]-square remove target so its circle sits [insetPx] inside the top-right
 * corner of an [iconPx]-square icon centred above a [labelPx]-tall label in [anchor], clamped
 * within [room].
 */
internal fun removeChipOffset(
    anchor: Bounds,
    iconPx: Int,
    touchPx: Int,
    insetPx: Int,
    room: IntSize,
    labelPx: Int = 0,
): IntOffset {
    val centerX = (anchor.left + anchor.right) / 2f
    val boxHeight = (anchor.height - labelPx).coerceAtLeast(0f)
    val iconTop = (anchor.top + (boxHeight - iconPx) / 2f).coerceAtLeast(anchor.top)
    val x = (centerX + iconPx / 2f - insetPx - touchPx / 2f).roundToInt()
    val y = (iconTop + insetPx - touchPx / 2f).roundToInt()
    return IntOffset(
        x.coerceIn(0, (room.width - touchPx).coerceAtLeast(0)),
        y.coerceIn(0, (room.height - touchPx).coerceAtLeast(0)),
    )
}
