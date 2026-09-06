package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.grayvines.runway.ui.drag.Point

/**
 * One page of cells: every item at its footprint, sized by its span. While a drag plans
 * displacement, displaced items are shown at their planned cells, and any change of cell slides
 * rather than jumps. The item being dragged, and the one settling after a drop, are drawn by
 * [DragOverlay] instead; their cells stay invisible here and the settling one reports where it is
 * so the overlay can bring the icon to it.
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
    val positions = mutableListOf<State<IntOffset>>()
    Layout(
        content = {
            items.forEach { item ->
                key(item.id) {
                    val f = drag?.previewFor(item.id) ?: item.footprint
                    val settlingHere = drag?.takeIf { it.settling?.itemId == item.id }
                    positions +=
                        animateIntOffsetAsState(
                            IntOffset(f.x * cellW, f.y * cellH),
                            // The overlay carries a settling item to its cell; the cell itself
                            // must already be there, not sliding over.
                            if (settlingHere != null) {
                                snap()
                            } else {
                                spring(stiffness = Spring.StiffnessMediumLow)
                            },
                            label = "cell",
                        )
                    ItemCell(
                        item,
                        iconSize = iconSize,
                        labels = labels,
                        onClick = { onLaunch(item) },
                        drag = handlersFor(item),
                        lifted = item.id == drag?.draggedId || settlingHere != null,
                        modifier =
                            if (settlingHere != null) {
                                Modifier.onGloballyPositioned {
                                    val c = it.boundsInRoot().center
                                    settlingHere.onSettleTargetPositioned(Point(c.x, c.y))
                                }
                            } else {
                                Modifier
                            },
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize(),
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
