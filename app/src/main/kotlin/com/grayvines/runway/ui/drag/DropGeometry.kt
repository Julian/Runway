package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
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

/**
 * The on-screen areas a drag can land in, reported by the UI as it lays out. Reported bounds
 * already include the drag zoom: positioned callbacks see layer transforms.
 */
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
 * offset) snaps to the nearest cell; the pointer's row decides which area is meant. A finger pushed
 * past a side (the zoomed area ends inside the screen edge) still drops into the edge column.
 */
fun DropAreas.targetFor(pointer: Point, grab: Point, spanX: Int, spanY: Int): DropTarget? {
    val topLeft = Point(pointer.x - grab.x, pointer.y - grab.y)
    val homeArea = home?.takeIf { pointer.y in it.top..it.bottom }
    val dockArea = dock?.takeIf { pointer.y in it.top..it.bottom }
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

/** Which side of an area a drag is hovering at. */
enum class Edge(val pageDelta: Int) {
    LEFT(-1),
    RIGHT(1),
}

/** An edge of the home pages or of the dock, being hovered. */
data class EdgeHover(val container: Container, val edge: Edge)

/**
 * The edge zone is the outer quarter of the outer column or slot, so the centre of an outer cell is
 * always a safe drop, however many columns there are.
 */
const val EDGE_CELL_FRACTION = 0.25f

/**
 * The home or dock edge under [pointer], for page flipping. Points beyond a side still count: under
 * the drag zoom the area's visual edge sits inside the screen edge, and fingers go to the screen
 * edge.
 */
fun DropAreas.edgeAt(pointer: Point): EdgeHover? =
    home?.edgeAt(pointer, columns)?.let { EdgeHover(Container.HOME, it) }
        ?: dock?.edgeAt(pointer, dockSlots)?.let { EdgeHover(Container.DOCK, it) }

private fun Bounds.edgeAt(pointer: Point, cellsAcross: Int): Edge? {
    if (pointer.y !in top..bottom) return null
    val zone = width / cellsAcross * EDGE_CELL_FRACTION
    return when {
        pointer.x < left + zone -> Edge.LEFT
        pointer.x > right - zone -> Edge.RIGHT
        else -> null
    }
}
