package com.grayvines.runway.model

/** Dimensions of one page, in cells. */
data class GridSize(val columns: Int, val rows: Int) {
    init {
        require(columns > 0 && rows > 0) { "grid must be at least 1×1" }
    }
}

data class Cell(val x: Int, val y: Int)

/** The cells an item covers: top-left corner plus span. */
data class Footprint(val x: Int, val y: Int, val width: Int = 1, val height: Int = 1) {
    init {
        require(width > 0 && height > 0) { "span must be at least 1×1" }
    }

    val isSingleCell: Boolean
        get() = width == 1 && height == 1
}

/**
 * An item on a page, as far as layout is concerned; [foldable] items take a dropped app in.
 * [identity] names what it is a placement of (an app, a folder), so a page can refuse a second
 * placement of the same thing; null for things that are only ever placed once.
 */
data class Placed(
    val id: Long,
    val footprint: Footprint,
    val foldable: Boolean = true,
    val identity: String? = null,
)

operator fun GridSize.contains(footprint: Footprint): Boolean =
    footprint.x >= 0 &&
        footprint.y >= 0 &&
        footprint.x + footprint.width <= columns &&
        footprint.y + footprint.height <= rows

fun Footprint.overlaps(other: Footprint): Boolean =
    x < other.x + other.width &&
        other.x < x + width &&
        y < other.y + other.height &&
        other.y < y + height
