package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.Settling

const val DRAG_OVERLAY_TAG = "drag-overlay"

/**
 * Draws the lifted cell above everything else, in root coordinates. While dragging it follows the
 * pointer at its lifted size. On release it carries the icon from where the finger let go to the
 * cell it belongs to (which reports its own position, so a page still zooming out is tracked) and
 * eases it back to resting size, then hands over to the cell. Drawing this here rather than in the
 * page means a release outside the page is not clipped on its way back. [lift] is how far the
 * pick-up has progressed, shared with the home area's pull-back so the two move as one.
 */
@Composable
fun DragOverlay(drag: DragSession, item: HomeItem?, cell: DpSize, iconSize: Dp, lift: Float) {
    val app = item?.app ?: return
    val state = drag.state
    val settling = drag.settling
    when {
        state != null ->
            OverlayCell(
                app,
                Point(state.pointer.x - state.grab.x, state.pointer.y - state.grab.y),
                cell,
                iconSize,
                DragMotion.lerp(DragMotion.PRESSED_SCALE, DragMotion.LIFTED_SCALE, lift),
            )
        settling != null ->
            Settle(
                app,
                settling,
                drag.settleTarget,
                cell,
                iconSize,
                onDone = { drag.onSettled(settling.itemId) },
            )
    }
}

@Composable
private fun Settle(
    app: AppEntry,
    settling: Settling,
    target: Point?,
    cell: DpSize,
    iconSize: Dp,
    onDone: () -> Unit,
) {
    val progress = remember(settling) { Animatable(0f) }
    // Dock slots and home cells differ in size, so aim centre at centre, not corner at corner.
    val half = with(LocalDensity.current) { Point(cell.width.toPx() / 2, cell.height.toPx() / 2) }
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
            Point(DragMotion.lerp(from.x, target.x, t), DragMotion.lerp(from.y, target.y, t))
        }
    val at = Point(centre.x - half.x, centre.y - half.y)
    OverlayCell(app, at, cell, iconSize, DragMotion.lerp(DragMotion.LIFTED_SCALE, 1f, t))
}

@Composable
private fun OverlayCell(
    app: AppEntry,
    at: Point,
    cell: DpSize,
    iconSize: Dp,
    scale: Float,
) {
    Box(
        modifier = Modifier.offset { IntOffset(at.x.toInt(), at.y.toInt()) }.size(cell),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            app,
            Modifier.size(iconSize)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .testTag(DRAG_OVERLAY_TAG),
        )
    }
}
