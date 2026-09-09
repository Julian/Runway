package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import com.grayvines.runway.model.Footprint
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
        homeArea != null && (spanX > columns || spanY > rows) -> {
            null // wider than the grid: nowhere to go
        }
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

/**
 * The root-pixel centre of [footprint] on the shown page of [container], if that page is laid out.
 */
fun DropAreas.centreOf(container: Container, footprint: Footprint): Point? =
    when (container) {
        Container.HOME ->
            home?.let { area ->
                val cellW = area.width / columns
                val cellH = area.height / rows
                Point(
                    area.left + (footprint.x + footprint.width / 2f) * cellW,
                    area.top + (footprint.y + footprint.height / 2f) * cellH,
                )
            }
        Container.DOCK ->
            dock?.let { area ->
                val slotW = area.width / dockSlots
                Point(area.left + (footprint.x + HALF) * slotW, area.top + area.height / 2f)
            }
        Container.DRAWER -> null
    }

/**
 * The middle of a cell, this share of it each way, is where a dropped app folds with what is there;
 * the ring around it still displaces, so an icon can be pushed aside without folding.
 */
const val FOLD_ZONE = 0.6f

/**
 * Once a drag is folding into a cell it keeps folding until the finger is nearly out of that cell:
 * getting onto an icon takes aim, staying on it should not.
 */
const val FOLD_KEEP_ZONE = 0.9f

/**
 * The cell the pointer itself is in, when it is well inside it (the middle [zone] of it): a drop
 * there means "onto what is here" rather than "next to it".
 */
fun DropAreas.cellUnder(pointer: Point, zone: Float = FOLD_ZONE): DropTarget? {
    val homeArea = home?.takeIf { pointer in it }
    val dockArea = dock?.takeIf { pointer in it }
    return when {
        homeArea != null -> {
            val cellW = homeArea.width / columns
            val cellH = homeArea.height / rows
            val x = ((pointer.x - homeArea.left) / cellW).coerceIn(0f, columns - EPSILON)
            val y = ((pointer.y - homeArea.top) / cellH).coerceIn(0f, rows - EPSILON)
            DropTarget.HomeCell(homePage, x.toInt(), y.toInt()).takeIf {
                x.wellInside(zone) && y.wellInside(zone)
            }
        }
        dockArea != null -> {
            val slot =
                ((pointer.x - dockArea.left) / (dockArea.width / dockSlots)).coerceIn(
                    0f,
                    dockSlots - EPSILON,
                )
            DropTarget.DockSlot(dockPage, slot.toInt()).takeIf { slot.wellInside(zone) }
        }
        else -> {
            null
        }
    }
}

/** Whether a cell coordinate's fraction lies within the middle [zone] of the cell. */
private fun Float.wellInside(zone: Float): Boolean {
    val within = this - floor(this)
    val margin = (1f - zone) / 2
    return within in margin..1f - margin
}

private const val EPSILON = 0.001f
private const val HALF = 0.5f

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
