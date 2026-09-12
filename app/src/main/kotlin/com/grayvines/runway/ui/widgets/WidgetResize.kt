package com.grayvines.runway.ui.widgets

import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.LayoutEngine
import com.grayvines.runway.model.Placed
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** An edge of a widget that can be pulled. */
enum class Edge {
    LEFT,
    TOP,
    RIGHT,
    BOTTOM;

    val horizontal: Boolean
        get() = this == LEFT || this == RIGHT
}

/** How big a widget may be made, in cells of one grid, and along which axes. */
data class ResizeLimits(
    val minWidth: Int,
    val minHeight: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val horizontal: Boolean,
    val vertical: Boolean,
) {
    /** The edges that have a handle. */
    val edges: List<Edge>
        get() =
            listOfNotNull(
                Edge.LEFT.takeIf { horizontal },
                Edge.TOP.takeIf { vertical },
                Edge.RIGHT.takeIf { horizontal },
                Edge.BOTTOM.takeIf { vertical },
            )
}

/**
 * The limits a provider's sizes put on a widget in a grid of [cellWidthDp] × [cellHeightDp] cells:
 * never fewer cells than cover its smallest size, never more than fit its largest (none named means
 * the grid is the limit), and never outside the grid. A widget that may not be resized along an
 * axis keeps its span there.
 */
fun resizeLimits(
    minWidthDp: Float,
    minHeightDp: Float,
    maxWidthDp: Float,
    maxHeightDp: Float,
    horizontal: Boolean,
    vertical: Boolean,
    cellWidthDp: Float,
    cellHeightDp: Float,
    grid: GridSize,
): ResizeLimits {
    val minWidth = ceil(minWidthDp / cellWidthDp).toInt().coerceIn(1, grid.columns)
    val minHeight = ceil(minHeightDp / cellHeightDp).toInt().coerceIn(1, grid.rows)
    return ResizeLimits(
        minWidth = minWidth,
        minHeight = minHeight,
        maxWidth = mostCells(maxWidthDp, cellWidthDp, grid.columns).coerceAtLeast(minWidth),
        maxHeight = mostCells(maxHeightDp, cellHeightDp, grid.rows).coerceAtLeast(minHeight),
        horizontal = horizontal,
        vertical = vertical,
    )
}

/** The cells [maxDp] holds, or all of them when the provider names no largest size. */
private fun mostCells(maxDp: Float, cellDp: Float, cells: Int) =
    if (maxDp <= 0f) cells else floor(maxDp / cellDp).toInt().coerceIn(1, cells)

/**
 * [current] with its [edge] pulled [cells] outward (inward when negative), as far as that can go:
 * within [limits], inside [grid], and over no cell of [others]. A pull past what is possible stops
 * where it must, so the widget grows as far as it can toward the finger; one that cannot move at
 * all leaves the footprint as it is.
 */
fun resized(
    current: Footprint,
    edge: Edge,
    cells: Int,
    limits: ResizeLimits,
    grid: GridSize,
    others: List<Placed>,
): Footprint {
    val allowed = if (edge.horizontal) limits.horizontal else limits.vertical
    if (!allowed) return current
    val pulls = if (cells >= 0) 1..cells else -1 downTo cells
    var best = current
    for (by in pulls) {
        best = current.pulled(edge, by)?.takeIf { it.fits(limits, grid, others) } ?: break
    }
    return best
}

/**
 * How far [edge] can move, in cells, from [current]: inward (negative) to outward (positive), as
 * far as the provider's sizes, the grid and the [others] allow. The outline a handle drags is kept
 * within this, so it never shows room the widget cannot take.
 */
fun reach(
    current: Footprint,
    edge: Edge,
    limits: ResizeLimits,
    grid: GridSize,
    others: List<Placed>,
): IntRange {
    val most = maxOf(grid.columns, grid.rows)
    fun along(f: Footprint) = if (edge.horizontal) f.width else f.height
    val outward = along(resized(current, edge, most, limits, grid, others)) - along(current)
    val inward = along(resized(current, edge, -most, limits, grid, others)) - along(current)
    return inward..outward
}

private fun Footprint.fits(limits: ResizeLimits, grid: GridSize, others: List<Placed>) =
    width in limits.minWidth..limits.maxWidth &&
        height in limits.minHeight..limits.maxHeight &&
        LayoutEngine.canPlace(grid, others, this)

/** This footprint with [edge] moved [by] cells outward, or null when that leaves no cell. */
private fun Footprint.pulled(edge: Edge, by: Int): Footprint? {
    val (newWidth, newHeight) =
        when (edge) {
            Edge.LEFT,
            Edge.RIGHT -> width + by to height
            Edge.TOP,
            Edge.BOTTOM -> width to height + by
        }
    if (newWidth < 1 || newHeight < 1) return null
    return when (edge) {
        Edge.LEFT -> Footprint(x - by, y, newWidth, newHeight)
        Edge.TOP -> Footprint(x, y - by, newWidth, newHeight)
        Edge.RIGHT,
        Edge.BOTTOM -> Footprint(x, y, newWidth, newHeight)
    }
}

/**
 * The whole cells a pull of [px] along an edge amounts to, snapping at half a cell, except that
 * leaving the [from] cells it snapped to last takes a little more than half: a finger resting on
 * the line does not flip the widget back and forth.
 */
fun cellsPulled(px: Float, cellPx: Float, from: Int = 0): Int {
    val exact = px / cellPx
    val whole = floor(abs(exact) + HALF).toInt().let { if (px < 0) -it else it }
    return if (whole != from && abs(exact - from) < HALF + HYSTERESIS) from else whole
}

private const val HALF = 0.5f
private const val HYSTERESIS = 0.1f
