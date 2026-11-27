package com.grayvines.runway.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.grayvines.runway.ui.drag.Bounds

private val MENU_WIDTH = 208.dp
private val MENU_GAP = 6.dp
private val CORNER = 20.dp
internal val ROW_ICON = 20.dp
private val ICON_BUTTON_TOUCH = 40.dp
internal const val ICON_ALPHA = 0.8f
private val SURFACE = Color(0xFF202124)

/**
 * A small menu of [content] rows beside [anchor] (root px): below it, or above it when there is no
 * room below, and centred on it sideways as far as the screen allows. [overlay] is drawn in the
 * same tagged layer (for a corner chip on the held cell). A tap anywhere else dismisses it, and so
 * does back.
 */
@Composable
internal fun Menu(
    anchor: Bounds,
    tag: String,
    onDismiss: () -> Unit,
    overlay: @Composable BoxScope.(room: IntSize) -> Unit = {},
    content: (@Composable ColumnScope.() -> Unit)?,
) {
    BackHandler(onBack = onDismiss)
    var room by remember { mutableStateOf(IntSize.Zero) }
    Box(Modifier.fillMaxSize().onSizeChanged { room = it }) {
        // A sibling, not a parent: a clickable parent would merge the menu's semantics into itself.
        Spacer(
            Modifier.fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = "Dismiss",
                    role = Role.Button,
                    onClick = onDismiss,
                )
                .semantics { contentDescription = "Dismiss" }
        )
        Box(Modifier.fillMaxSize().testTag(tag), contentAlignment = AbsoluteAlignment.TopLeft) {
            if (content != null && room != IntSize.Zero) {
                MenuCard(anchor, room, content)
            }
            if (room != IntSize.Zero) overlay(room)
        }
    }
}

@Composable
private fun MenuCard(anchor: Bounds, room: IntSize, content: @Composable ColumnScope.() -> Unit) {
    val sysBars = WindowInsets.systemBars
    val ime = WindowInsets.ime
    Surface(
        modifier =
            Modifier.layout { measurable, constraints ->
                    // Read system bars and keyboard in the layout phase so IME frames only
                    // re-layout this surface rather than recomposing the menu.
                    val topInset = sysBars.getTop(this)
                    val bottomInset = maxOf(ime.getBottom(this), sysBars.getBottom(this))
                    val clearHeight = (room.height - bottomInset).coerceAtLeast(0)
                    val maxMenuHeight = (clearHeight - topInset).coerceAtLeast(0)
                    val placeable =
                        measurable.measure(
                            constraints.copy(
                                minHeight = 0,
                                maxHeight = minOf(constraints.maxHeight, maxMenuHeight),
                            )
                        )
                    val pos =
                        menuOffset(
                            anchor,
                            IntSize(placeable.width, placeable.height),
                            IntSize(room.width, clearHeight),
                            MENU_GAP.roundToPx(),
                            topInset = topInset,
                        )
                    layout(placeable.width, placeable.height) { placeable.place(pos) }
                }
                .width(MENU_WIDTH),
        shape = RoundedCornerShape(CORNER),
        color = SURFACE,
        contentColor = Color.White,
        shadowElevation = 12.dp,
    ) {
        // Scrolls when there are more rows than room.
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            content = content,
        )
    }
}

/**
 * Where a menu of [size] goes beside [anchor] within [room] (the part of the screen above the
 * keyboard and navigation bar, below [topInset]): below the anchor by [gap] when it fits, else
 * above it; centred on it sideways as far as the room allows; never past the room's edges, top and
 * bottom included.
 */
internal fun menuOffset(
    anchor: Bounds,
    size: IntSize,
    room: IntSize,
    gap: Int,
    topInset: Int = 0,
): IntOffset {
    val x =
        (anchor.left.toInt() + (anchor.width.toInt() - size.width) / 2).coerceIn(
            0,
            (room.width - size.width).coerceAtLeast(0),
        )
    val below = anchor.bottom.toInt() + gap
    val maxY = (room.height - size.height).coerceAtLeast(topInset)
    val y =
        if (below + size.height <= room.height) {
            below
        } else {
            anchor.top.toInt() - gap - size.height
        }
    return IntOffset(x, y.coerceIn(topInset, maxY))
}

@Composable
internal fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(role = Role.Button, onClick = onClick)
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

@Composable
internal fun MenuIconButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier =
            Modifier.size(ICON_BUTTON_TOUCH)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = ICON_ALPHA),
            modifier = Modifier.size(ROW_ICON),
        )
    }
}
