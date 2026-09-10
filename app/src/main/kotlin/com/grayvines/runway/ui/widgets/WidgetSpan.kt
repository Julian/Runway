package com.grayvines.runway.ui.widgets

import com.grayvines.runway.model.GridSize
import kotlin.math.ceil

/** A widget's size in cells. */
data class WidgetSpan(val width: Int, val height: Int)

/**
 * The sizes to try for a new widget, in order. First the cells its provider designed it for
 * ([target], null when it names none), if the grid is that big, and never smaller than its minimum
 * size allows; then the smallest span whose cells, [cellWidthDp] × [cellHeightDp] each, cover that
 * minimum. The first with room on the page wins.
 */
fun spansFor(
    target: WidgetSpan?,
    minWidthDp: Float,
    minHeightDp: Float,
    cellWidthDp: Float,
    cellHeightDp: Float,
    grid: GridSize,
): List<WidgetSpan> {
    val least =
        WidgetSpan(
            ceil(minWidthDp / cellWidthDp).toInt().coerceIn(1, grid.columns),
            ceil(minHeightDp / cellHeightDp).toInt().coerceIn(1, grid.rows),
        )
    val designed =
        target
            ?.takeIf { it.width in 1..grid.columns && it.height in 1..grid.rows }
            ?.let { WidgetSpan(maxOf(it.width, least.width), maxOf(it.height, least.height)) }
    return listOfNotNull(designed, least).distinct()
}
