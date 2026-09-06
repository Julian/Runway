package com.grayvines.runway.ui.menu

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.Container
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.home.AppIcon
import com.grayvines.runway.ui.home.HomeItem

const val ITEM_MENU_TAG = "item-menu"

private val MENU_WIDTH = 208.dp
private val MENU_GAP = 6.dp
private val CORNER = 20.dp
private val HEADER_ICON = 28.dp
private val ROW_ICON = 20.dp
private const val ICON_ALPHA = 0.8f
private const val DIVIDER_ALPHA = 0.12f
private val SURFACE = Color(0xFF202124)

/** The item a long press landed on, where its cell is (root px), and where it lives. */
data class ItemMenuState(
    val item: HomeItem,
    val container: Container,
    val page: Int,
    val anchor: Bounds,
)

/** What the menu can do to its item. */
class ItemMenuActions(
    val appInfo: () -> Unit,
    val uninstall: () -> Unit,
    val remove: () -> Unit,
)

/**
 * A small menu beside a long-pressed cell, in root coordinates: below the cell, or above it when
 * there is no room below. A tap anywhere else dismisses it.
 */
@Composable
fun ItemMenu(state: ItemMenuState, actions: ItemMenuActions, onDismiss: () -> Unit) {
    var room by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { room = it }) {
        // A sibling, not a parent: a clickable parent would merge the menu's semantics into itself.
        Spacer(
            Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
        var size by remember { mutableStateOf(IntSize.Zero) }
        val gap = with(LocalDensity.current) { MENU_GAP.roundToPx() }
        Surface(
            modifier =
                Modifier.offset {
                        val x =
                            (state.anchor.left.toInt() +
                                    (state.anchor.width.toInt() - size.width) / 2)
                                .coerceIn(0, (room.width - size.width).coerceAtLeast(0))
                        val below = state.anchor.bottom.toInt() + gap
                        val y =
                            if (below + size.height <= room.height) {
                                below
                            } else {
                                state.anchor.top.toInt() - gap - size.height
                            }
                        IntOffset(x, y.coerceAtLeast(0))
                    }
                    .width(MENU_WIDTH)
                    .onSizeChanged { size = it }
                    .testTag(ITEM_MENU_TAG),
            shape = RoundedCornerShape(CORNER),
            color = SURFACE,
            contentColor = Color.White,
            shadowElevation = 12.dp,
        ) {
            Column(Modifier.padding(vertical = 6.dp)) {
                state.item.app?.let { app ->
                    Header(app, state.item.label)
                    HorizontalDivider(color = Color.White.copy(alpha = DIVIDER_ALPHA))
                    MenuRow("App info", Icons.Outlined.Info, actions.appInfo)
                    MenuRow("Uninstall", Icons.Outlined.Delete, actions.uninstall)
                }
                MenuRow("Remove", Icons.Outlined.Clear, actions.remove)
            }
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

@Composable
private fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = ICON_ALPHA),
            modifier = Modifier.size(ROW_ICON),
        )
        Spacer(Modifier.width(16.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}
