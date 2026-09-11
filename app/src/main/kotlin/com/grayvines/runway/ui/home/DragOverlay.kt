package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.ui.drag.DragState
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.Settling

const val DRAG_OVERLAY_TAG = "drag-overlay"

/** A widget carried before its picture could be taken: a faint slab the size of it. */
private const val BLANK_WIDGET_ALPHA = 0.3f
private val BLANK_WIDGET_CORNER = 16.dp

/**
 * Draws the lifted cell above everything else, in root coordinates. While dragging it follows the
 * pointer at its lifted size. On release it carries the icon from where the finger let go to the
 * cell it belongs to (which reports its own position, so a page still zooming out is tracked) and
 * eases it back to resting size, then hands over to the cell. Drawing this here rather than in the
 * page means a release outside the page is not clipped on its way back. [lift] is how far the
 * pick-up has progressed, shared with the home area's pull-back so the two move as one. A widget is
 * drawn at the size of its cells, from the [picture] its cell took as it lifted.
 */
@Composable
fun DragOverlay(
    drag: DragSession,
    item: HomeItem?,
    picture: ImageBitmap?,
    cell: DpSize,
    iconSize: Dp,
    lift: () -> Float,
) {
    if (item == null) return
    // Which phase, and whether a fold is on: derived, so the finger's moves change nothing here.
    val dragging by remember(drag) { derivedStateOf { drag.state != null } }
    val folds by remember(drag) { derivedStateOf { drag.foldTargetId != null } }
    val settling = drag.settling
    val size = DpSize(cell.width * item.spanX, cell.height * item.spanY)
    when {
        dragging -> {
            val folding by
                animateFloatAsState(
                    if (folds) DragMotion.FOLDING_SCALE else 1f,
                    tween(DragMotion.FOLDING_MS),
                    label = "folding",
                )
            // Picked up out of a cell mid-drag: the icon slides from there into the finger.
            val pickedUpFrom = drag.state?.pickedUpFrom
            val arrival =
                remember(pickedUpFrom) { Animatable(if (pickedUpFrom == null) 1f else 0f) }
            LaunchedEffect(pickedUpFrom) {
                if (pickedUpFrom != null) arrival.animateTo(1f, DragMotion.lift)
            }
            val half =
                with(LocalDensity.current) { Point(size.width.toPx() / 2, size.height.toPx() / 2) }
            OverlayCell(
                item,
                picture,
                at = { drag.state?.carried(pickedUpFrom, half, arrival.value) ?: Point(0f, 0f) },
                size,
                iconSize,
                scale = { item.liftedScale(lift()) * folding },
            )
        }
        settling != null -> {
            Settle(
                item,
                picture,
                settling,
                drag.settleTarget,
                size,
                iconSize,
                onDone = { drag.onSettled(settling.itemId) },
            )
        }
    }
}

/**
 * Where the carried item's cell is drawn: under the finger, less the grab; or, just picked up out
 * of a cell at [pickedUpFrom] (centre), [arrival] of the way from there to under the finger.
 */
private fun DragState.carried(pickedUpFrom: Point?, half: Point, arrival: Float): Point {
    val under = Point(pointer.x - grab.x, pointer.y - grab.y)
    if (pickedUpFrom == null || arrival >= 1f) return under
    val from = Point(pickedUpFrom.x - half.x, pickedUpFrom.y - half.y)
    return Point(lerp(from.x, under.x, arrival), lerp(from.y, under.y, arrival))
}

@Composable
private fun Settle(
    item: HomeItem,
    picture: ImageBitmap?,
    settling: Settling,
    target: Point?,
    size: DpSize,
    iconSize: Dp,
    onDone: () -> Unit,
) {
    val progress = remember(settling) { Animatable(0f) }
    // Dock slots and home cells differ in size, so aim centre at centre, not corner at corner.
    val half = with(LocalDensity.current) { Point(size.width.toPx() / 2, size.height.toPx() / 2) }
    val from = Point(settling.from.x + half.x, settling.from.y + half.y)
    // Wait for the destination cell to report where it is before setting off.
    LaunchedEffect(settling, target != null) {
        if (target != null) {
            progress.animateTo(1f, DragMotion.settle)
            onDone()
        }
    }
    val t = progress.value
    val centre =
        if (target == null) {
            from
        } else {
            Point(lerp(from.x, target.x, t), lerp(from.y, target.y, t))
        }
    val at = Point(centre.x - half.x, centre.y - half.y)
    OverlayCell(
        item,
        picture,
        { at },
        size,
        iconSize,
        scale = { lerp(item.liftedScale(1f), 1f, t) },
    )
}

/**
 * How big the carried item is drawn, [lift] of the way up: an icon from pressed to lifted, a widget
 * from its own size to a little more. Only an icon folds, so only an icon is scaled by it.
 */
private fun HomeItem.liftedScale(lift: Float): Float =
    if (kind == ItemKind.WIDGET) {
        lerp(1f, DragMotion.WIDGET_LIFTED_SCALE, lift)
    } else {
        lerp(DragMotion.PRESSED_SCALE, DragMotion.LIFTED_SCALE, lift)
    }

/**
 * The carried item at [at] (root px, top-left), in a box of [size], at [scale]: an icon, or a
 * widget's [picture].
 */
@Composable
private fun OverlayCell(
    item: HomeItem,
    picture: ImageBitmap?,
    at: () -> Point,
    size: DpSize,
    iconSize: Dp,
    scale: () -> Float,
) {
    Box(
        modifier =
            Modifier.offset {
                    val p = at()
                    IntOffset(p.x.toInt(), p.y.toInt())
                }
                .size(size),
        contentAlignment = Alignment.Center,
    ) {
        if (item.kind == ItemKind.WIDGET) {
            val grown =
                Modifier.fillMaxSize()
                    .graphicsLayer {
                        val s = scale()
                        scaleX = s
                        scaleY = s
                    }
                    .testTag(DRAG_OVERLAY_TAG)
            if (picture != null) {
                Image(picture, contentDescription = item.label, modifier = grown)
            } else {
                Box(
                    grown.background(
                        Color.White.copy(alpha = BLANK_WIDGET_ALPHA),
                        RoundedCornerShape(BLANK_WIDGET_CORNER),
                    )
                )
            }
        } else {
            ItemIcon(
                item,
                Modifier.size(iconSize)
                    .graphicsLayer {
                        val s = scale()
                        scaleX = s
                        scaleY = s
                    }
                    .testTag(DRAG_OVERLAY_TAG),
            )
        }
    }
}
