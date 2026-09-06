package com.grayvines.runway.ui.drag

import kotlin.math.floor
import kotlin.math.roundToInt

/** An axis-aligned box in root pixels. */
data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float
        get() = right - left

    val height: Float
        get() = bottom - top
}

operator fun Bounds.contains(p: Point): Boolean = p.x in left..right && p.y in top..bottom

/** The on-screen areas a drag can land in, reported by the UI as it lays out. */
data class DropAreas(
    val home: Bounds? = null,
    val homePage: Int = 0,
    val columns: Int = 1,
    val rows: Int = 1,
    val dock: Bounds? = null,
    val dockPage: Int = 0,
    val dockSlots: Int = 1,
    /** Visual zoom applied to the areas while dragging, about [zoomPivot]; bounds are unzoomed. */
    val zoom: Float = 1f,
    val zoomPivot: Point = Point(0f, 0f),
)

/**
 * Maps the dragged item's position to a drop target. The item's top-left corner (pointer minus grab
 * offset) snaps to the nearest cell; the pointer itself decides which area is meant.
 */
fun DropAreas.targetFor(pointer: Point, grab: Point, spanX: Int, spanY: Int): DropTarget? =
    unzoomed(pointer).let { p ->
        targetForUnzoomed(p, Point(p.x - grab.x / zoom, p.y - grab.y / zoom), spanX, spanY)
    }

/** Maps a screen point back into the unzoomed coordinates the bounds were reported in. */
private fun DropAreas.unzoomed(p: Point) =
    Point(zoomPivot.x + (p.x - zoomPivot.x) / zoom, zoomPivot.y + (p.y - zoomPivot.y) / zoom)

private fun DropAreas.targetForUnzoomed(
    pointer: Point,
    topLeft: Point,
    spanX: Int,
    spanY: Int,
): DropTarget? {
    val homeArea = home?.takeIf { pointer in it }
    val dockArea = dock?.takeIf { pointer in it }
    return when {
        homeArea != null -> {
            val cellW = homeArea.width / columns
            val cellH = homeArea.height / rows
            val x = ((topLeft.x - homeArea.left) / cellW).roundToInt().coerceIn(0, columns - spanX)
            val y = ((topLeft.y - homeArea.top) / cellH).roundToInt().coerceIn(0, rows - spanY)
            DropTarget.HomeCell(homePage, x, y)
        }
        dockArea != null -> {
            val slotW = dockArea.width / dockSlots
            val slot = floor((pointer.x - dockArea.left) / slotW).toInt().coerceIn(0, dockSlots - 1)
            DropTarget.DockSlot(dockPage, slot)
        }
        else -> {
            null
        }
    }
}
