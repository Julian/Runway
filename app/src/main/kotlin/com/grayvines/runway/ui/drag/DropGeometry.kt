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
)

/**
 * Maps the dragged item's position to a drop target. The item's top-left corner (pointer minus grab
 * offset) snaps to the nearest cell; the pointer itself decides which area is meant.
 */
fun DropAreas.targetFor(pointer: Point, grab: Point, spanX: Int, spanY: Int): DropTarget? {
    val topLeft = Point(pointer.x - grab.x, pointer.y - grab.y)
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
