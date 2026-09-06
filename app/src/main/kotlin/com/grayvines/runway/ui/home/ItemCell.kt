package com.grayvines.runway.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Point

private const val ICON_BITMAP_SIZE = 256

/**
 * Drag callbacks; positions are root pixels. [onStart] also gets the grab point within the cell.
 */
class DragHandlers(
    val onStart: (pointer: Point, grab: Point) -> Unit,
    val onMove: (pointer: Point) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/** One cell's content: an app icon of [iconSize] (optionally labelled), or a placeholder. */
@Composable
fun ItemCell(
    item: HomeItem,
    iconSize: Dp,
    labels: Boolean,
    onClick: () -> Unit,
    drag: DragHandlers? = null,
    lifted: Boolean = false,
) {
    // The gesture coroutine outlives recompositions: both of these must always be current.
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val handlers by rememberUpdatedState(drag)
    Column(
        modifier =
            Modifier.fillMaxSize()
                .onGloballyPositioned { coords = it }
                .clickable(onClick = onClick)
                .then(
                    if (drag == null) {
                        Modifier
                    } else {
                        Modifier.pointerInput(item.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { local ->
                                    val root = coords?.localToRoot(local) ?: local
                                    handlers?.onStart(root.toPoint(), local.toPoint())
                                },
                                onDrag = { change, _ ->
                                    val root =
                                        coords?.localToRoot(change.position) ?: change.position
                                    handlers?.onMove(root.toPoint())
                                },
                                onDragEnd = { handlers?.onEnd() },
                                onDragCancel = { handlers?.onCancel() },
                            )
                        }
                    }
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Invisible while being dragged: removing the cell would cancel its own gesture.
        Box(
            Modifier.weight(1f).fillMaxWidth().alpha(if (lifted) 0f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            if (item.app != null) {
                AppIcon(item.app, Modifier.size(iconSize))
            } else {
                Placeholder(item.kind)
            }
        }
        if (labels && !lifted) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}

@Composable
internal fun AppIcon(app: AppEntry, modifier: Modifier = Modifier) {
    val bitmap =
        remember(app.key) { app.icon.toBitmap(ICON_BITMAP_SIZE, ICON_BITMAP_SIZE).asImageBitmap() }
    Image(bitmap = bitmap, contentDescription = app.label, modifier = modifier)
}

@Composable
private fun Placeholder(kind: ItemKind) {
    Text(kind.name, color = Color.White, style = MaterialTheme.typography.labelSmall)
}

private fun Offset.toPoint() = Point(x, y)
