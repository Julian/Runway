package com.grayvines.runway.ui.menu

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.grayvines.runway.ui.drag.Bounds

private val MENU_WIDTH = 208.dp
private val MENU_GAP = 6.dp
private val CORNER = 20.dp
private val ROW_ICON = 20.dp
private const val ICON_ALPHA = 0.8f
private val SURFACE = Color(0xFF202124)

/**
 * A small menu of [content] rows beside [anchor] (root px): below it, or above it when there is no
 * room below, and centred on it sideways as far as the screen allows. A tap anywhere else dismisses
 * it, and so does back.
 */
@Composable
internal fun Menu(
    anchor: Bounds,
    tag: String,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
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
                    onClick = onDismiss,
                )
        )
        var size by remember { mutableStateOf(IntSize.Zero) }
        val density = LocalDensity.current
        val gap = with(density) { MENU_GAP.roundToPx() }
        // The keyboard covers the bottom of the room: the menu keeps clear of it, as it does of
        // the screen's edges, or its rows could not be reached.
        val keyboard = with(density) { WindowInsets.ime.getBottom(this) }
        val clear = IntSize(room.width, (room.height - keyboard).coerceAtLeast(0))
        Surface(
            modifier =
                Modifier.offset { menuOffset(anchor, size, clear, gap) }
                    .width(MENU_WIDTH)
                    .heightIn(max = with(density) { clear.height.toDp() })
                    .onSizeChanged { size = it }
                    // Not yet measured: not yet placed. One frame in the wrong place would show.
                    .graphicsLayer { alpha = if (size == IntSize.Zero) 0f else 1f }
                    .testTag(tag),
            shape = RoundedCornerShape(CORNER),
            color = SURFACE,
            contentColor = Color.White,
            shadowElevation = 12.dp,
        ) {
            // Scrolls when there are more rows than room, as with many drawer folders to add to.
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
                content = content,
            )
        }
    }
}

/**
 * Where a menu of [size] goes beside [anchor] within [room] (the part of the screen the keyboard
 * leaves clear): below the anchor by [gap] when it fits, else above it; centred on it sideways as
 * far as the room allows; never past the room's edges, top and bottom included.
 */
internal fun menuOffset(anchor: Bounds, size: IntSize, room: IntSize, gap: Int): IntOffset {
    val x =
        (anchor.left.toInt() + (anchor.width.toInt() - size.width) / 2).coerceIn(
            0,
            (room.width - size.width).coerceAtLeast(0),
        )
    val below = anchor.bottom.toInt() + gap
    val y =
        if (below + size.height <= room.height) below else anchor.top.toInt() - gap - size.height
    return IntOffset(x, y.coerceIn(0, (room.height - size.height).coerceAtLeast(0)))
}

@Composable
internal fun MenuRow(label: String, icon: ImageVector, onClick: () -> Unit) {
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
