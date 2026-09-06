package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import com.grayvines.runway.ui.drag.Point

/**
 * One page of cells: every item at its footprint, sized by its span. While a drag plans
 * displacement, displaced items are shown at their planned cells, and any change of cell slides
 * rather than jumps. A just-dropped item starts exactly where the finger released it and slides
 * from there into its cell; nothing ever returns to where it came from.
 */
@Composable
internal fun GridPage(
    items: List<HomeItem>,
    cell: DpSize,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem) -> Unit,
    drag: DragSession?,
    handlersFor: (HomeItem) -> DragHandlers?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val cellW = with(density) { cell.width.roundToPx() }
    val cellH = with(density) { cell.height.roundToPx() }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val positions = mutableListOf<State<IntOffset>>()
    Layout(
        content = {
            items.forEach { item ->
                key(item.id) {
                    val f = drag?.previewFor(item.id) ?: item.footprint
                    val target = IntOffset(f.x * cellW, f.y * cellH)
                    // Converted once per drop: the page's coordinates keep changing while the drag
                    // zoom animates out, and a start point that moved would restart the settle.
                    val releasedAt = drag?.settleFrom(item.id)
                    val settleFrom =
                        remember(releasedAt) { releasedAt?.let { coords?.toLocal(it) } }
                    positions += cellPosition(target, settleFrom) { drag?.onSettled(item.id) }
                    ItemCell(
                        item,
                        iconSize = iconSize,
                        labels = labels,
                        onClick = { onLaunch(item) },
                        drag = handlersFor(item),
                        lifted = item.id == drag?.draggedId,
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize().onGloballyPositioned { coords = it },
    ) { measurables, constraints ->
        val placeables = measurables.mapIndexed { i, measurable ->
            val f = items[i].footprint
            measurable.measure(Constraints.fixed(f.width * cellW, f.height * cellH))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable -> placeable.place(positions[i].value) }
        }
    }
}

/**
 * Where a cell is drawn: slides to [target] on any change. When [settleFrom] is given the item was
 * just dropped there, so on that very frame it is drawn there and then slides to [target].
 */
@Composable
private fun cellPosition(
    target: IntOffset,
    settleFrom: IntOffset?,
    onSettled: () -> Unit,
): State<IntOffset> {
    val slide = remember { Animatable(target, IntOffset.VectorConverter) }
    val settle = remember(settleFrom) { Animatable(0f) }
    LaunchedEffect(target, settleFrom) {
        if (settleFrom != null) {
            slide.snapTo(target) // whatever slide was underway is moot: the item is at [settleFrom]
            settle.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
            onSettled()
        } else {
            slide.animateTo(target, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    return remember(target, settleFrom) {
        derivedStateOf {
            if (settleFrom == null) {
                slide.value
            } else {
                lerp(settleFrom, target, settle.value)
            }
        }
    }
}

private fun lerp(from: IntOffset, to: IntOffset, t: Float) =
    IntOffset((from.x + (to.x - from.x) * t).toInt(), (from.y + (to.y - from.y) * t).toInt())

/** A root-pixel point in this layout's own coordinates (accounting for the drag zoom). */
private fun LayoutCoordinates.toLocal(root: Point): IntOffset =
    localPositionOf(findRootCoordinates(), Offset(root.x, root.y)).round()
