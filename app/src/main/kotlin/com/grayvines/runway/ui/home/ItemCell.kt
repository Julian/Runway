package com.grayvines.runway.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.Point

/** A folder shows its first few apps in a little grid on a dim tile. */
private const val FOLDER_PREVIEW_COLUMNS = 2
private const val FOLDER_PREVIEW_COUNT = 4
private const val FOLDER_TILE_ALPHA = 0.35f
private const val FOLDER_TILE_INSET = 0.12f

/**
 * An icon about to take a dropped app in shrinks to the size it would have on a folder tile, and
 * the tile fades in behind it: what a drop would make, shown before it is made.
 */
private const val RECEIVING_SCALE = 0.55f
private const val HINT_MS = 150

const val FOLD_HINT_TAG = "fold-hint"

/**
 * Long-press callbacks; positions are root pixels. [onHold] fires when the finger has rested long
 * enough, with the cell's bounds; [onStart] when it then moves, with the grab point within the
 * cell. Everything after the start is tracked from the root, not by the cell.
 */
class DragHandlers(
    val onHold: (cell: Bounds) -> Unit,
    val onStart: (pointer: Point, grab: Point) -> Unit,
)

/** One cell's content: an app icon of [iconSize] (optionally labelled), or a placeholder. */
@Composable
fun ItemCell(
    item: HomeItem,
    iconSize: Dp,
    labels: Boolean,
    onClick: (cell: Bounds) -> Unit,
    modifier: Modifier = Modifier,
    drag: DragHandlers? = null,
    lifted: Boolean = false,
    receiving: Boolean = false,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val pressScale by
        animateFloatAsState(
            when {
                receiving -> RECEIVING_SCALE
                pressed -> DragMotion.PRESSED_SCALE
                else -> 1f
            },
            label = "press",
        )
    AppTile(
        item.label,
        labelled = labels && !lifted,
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { coords = it }
                .clickable(interactionSource = interactions, indication = null) {
                    onClick(coords?.boundsInRoot()?.toBounds() ?: Bounds(0f, 0f, 0f, 0f))
                }
                .liftable(item.id, drag),
    ) {
        // Invisible while being dragged: removing the cell would cancel its own gesture.
        Box(
            Modifier.weight(1f).fillMaxWidth().alpha(if (lifted) 0f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            FoldHint(receiving, Modifier.size(iconSize))
            ItemIcon(
                item,
                Modifier.size(iconSize).graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                },
            )
        }
    }
}

/** The folder tile that a hovering app would make here, fading in and out with [shown]. */
@Composable
private fun FoldHint(shown: Boolean, modifier: Modifier) {
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(HINT_MS), label = "fold hint")
    if (alpha > 0f) {
        Box(modifier.graphicsLayer { this.alpha = alpha }.folderTile().testTag(FOLD_HINT_TAG))
    }
}

/** The dim rounded square every folder sits on. */
private fun Modifier.folderTile() =
    clip(RoundedCornerShape(percent = 25)).background(Color.White.copy(alpha = FOLDER_TILE_ALPHA))

/** What an item looks like anywhere it is drawn: its app's icon, a folder tile, or a stand-in. */
@Composable
internal fun ItemIcon(item: HomeItem, modifier: Modifier = Modifier) {
    when {
        item.app != null -> AppIcon(item.app, modifier)
        item.kind == ItemKind.FOLDER -> FolderIcon(item.folder, item.label, modifier)
        else -> Placeholder(item.kind)
    }
}

/** The first few apps of a folder on a dim rounded tile, described by the folder's [name]. */
@Composable
internal fun FolderIcon(apps: List<AppEntry>, name: String, modifier: Modifier = Modifier) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(FOLDER_PREVIEW_COLUMNS),
        userScrollEnabled = false,
        modifier =
            modifier
                .semantics { contentDescription = name }
                .folderTile()
                .padding(fraction = FOLDER_TILE_INSET),
    ) {
        items(apps.take(FOLDER_PREVIEW_COUNT), key = { it.key }) { app ->
            // Part of the tile, which the folder's name describes: not an icon in its own right.
            AppIcon(app, Modifier.fillMaxWidth().padding(2.dp), described = false)
        }
    }
}

/** Padding as a share of the size the modifier is given; folder tiles come in every icon size. */
private fun Modifier.padding(fraction: Float): Modifier = layout { measurable, constraints ->
    val inset = (constraints.maxWidth * fraction).toInt()
    val placeable =
        measurable.measure(
            constraints.copy(
                maxWidth = constraints.maxWidth - 2 * inset,
                maxHeight = constraints.maxHeight - 2 * inset,
                minWidth = 0,
                minHeight = 0,
            )
        )
    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(inset, inset) }
}

@Composable
internal fun AppIcon(app: AppEntry, modifier: Modifier = Modifier, described: Boolean = true) {
    Image(
        bitmap = app.bitmap,
        contentDescription = app.label.takeIf { described },
        modifier = modifier,
    )
}

@Composable
private fun Placeholder(kind: ItemKind) {
    Text(kind.name, color = Color.White, style = MaterialTheme.typography.labelSmall)
}

/**
 * A long press holds (the menu appears); moving past touch slop after that starts a drag from where
 * the finger first rested. Positions are converted to root pixels for the handlers.
 */
internal fun Modifier.dragAfterLongPress(
    key: Any,
    coords: () -> LayoutCoordinates?,
    handlers: () -> DragHandlers?,
): Modifier =
    if (handlers() == null) {
        this
    } else {
        pointerInput(key) {
            val hold = HoldThenDrag(viewConfiguration.touchSlop, coords, handlers)
            detectDragGesturesAfterLongPress(
                onDragStart = hold::held,
                onDrag = { change, _ -> hold.moved(change.position) },
            )
        }
    }

/** One long press: held, then possibly dragged once the finger has moved past [slop]. */
private class HoldThenDrag(
    private val slop: Float,
    private val coords: () -> LayoutCoordinates?,
    private val handlers: () -> DragHandlers?,
) {
    private var grab = Offset.Zero
    private var dragging = false

    fun held(local: Offset) {
        grab = local
        dragging = false
        coords()?.let { handlers()?.onHold(it.boundsInRoot().toBounds()) }
    }

    fun moved(local: Offset) {
        if (dragging || (local - grab).getDistance() <= slop) return
        dragging = true
        val root = coords()?.localToRoot(local) ?: local
        handlers()?.onStart(root.toPoint(), grab.toPoint())
    }
}

private fun Offset.toPoint() = Point(x, y)
