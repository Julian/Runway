package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.drag.Bounds
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
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
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
                    val cellDrag = rememberCellDrag(item, drag)
                    val settlingHere = cellDrag.settlingHere
                    val f = cellDrag.footprint
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
                        onClick = { cell -> onLaunch(item, cell) },
                        drag = handlersFor(item),
                        lifted = cellDrag.lifted || settlingHere != null,
                        receiving = cellDrag.receiving,
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

/**
 * What the drag means for one cell, derived: a finger's every move changes the drag state, but only
 * a cell whose own answer changes needs to recompose.
 */
private class CellDrag(
    footprint: State<Footprint>,
    settlingHere: State<DragSession?>,
    lifted: State<Boolean>,
    receiving: State<Boolean>,
) {
    val footprint by footprint
    val settlingHere by settlingHere
    val lifted by lifted
    val receiving by receiving
}

@Composable
private fun rememberCellDrag(item: HomeItem, drag: DragSession?): CellDrag =
    remember(item, drag) {
        CellDrag(
            footprint = derivedStateOf { drag?.previewFor(item.id) ?: item.footprint },
            settlingHere = derivedStateOf { drag?.takeIf { it.settling?.itemId == item.id } },
            lifted = derivedStateOf { item.id == drag?.draggedId },
            receiving = derivedStateOf { item.id == drag?.foldTargetId },
        )
    }
