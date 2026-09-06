package com.grayvines.runway.model

/** Placement rules for one page. Pure Kotlin. */
object LayoutEngine {

    /** True if [footprint] is inside [grid] and overlaps no item except those in [ignoring]. */
    fun canPlace(
        grid: GridSize,
        items: List<Placed>,
        footprint: Footprint,
        ignoring: Set<Long> = emptySet(),
    ): Boolean =
        footprint in grid && items.all { it.id in ignoring || !it.footprint.overlaps(footprint) }

    /** First free top-left cell for a [width]×[height] item, scanning rows top to bottom. */
    fun findFreeCell(grid: GridSize, items: List<Placed>, width: Int = 1, height: Int = 1): Cell? {
        for (y in 0..grid.rows - height) {
            for (x in 0..grid.columns - width) {
                if (canPlace(grid, items, Footprint(x, y, width, height))) return Cell(x, y)
            }
        }
        return null
    }

    /**
     * New footprints for the single-cell items that must move so [movingId] can land on [target],
     * or null if the drop is impossible (off-grid, or blocked by a multi-cell item).
     */
    fun displaceFor(
        grid: GridSize,
        items: List<Placed>,
        movingId: Long,
        target: Footprint,
    ): Map<Long, Footprint>? {
        if (target !in grid) return null
        val blockers = items.filter { it.id != movingId && it.footprint.overlaps(target) }
        if (blockers.any { !it.footprint.isSingleCell }) return null

        val blockerIds = blockers.map { it.id }.toSet()
        var occupied =
            items.filter { it.id != movingId && it.id !in blockerIds } + Placed(movingId, target)
        val moves = mutableMapOf<Long, Footprint>()
        for (blocker in blockers) {
            val cell = findFreeCell(grid, occupied) ?: break
            val footprint = Footprint(cell.x, cell.y)
            moves[blocker.id] = footprint
            occupied = occupied + Placed(blocker.id, footprint)
        }
        return moves.takeIf { it.size == blockers.size }
    }

    /** How far [id] can grow along each axis independently, keeping its top-left corner. */
    fun resizeBounds(grid: GridSize, items: List<Placed>, id: Long): ResizeBounds? {
        val item = items.firstOrNull { it.id == id } ?: return null
        val f = item.footprint
        var maxWidth = f.width
        while (canPlace(grid, items, f.copy(width = maxWidth + 1), ignoring = setOf(id))) maxWidth++
        var maxHeight = f.height
        while (
            canPlace(grid, items, f.copy(height = maxHeight + 1), ignoring = setOf(id))
        ) maxHeight++
        return ResizeBounds(maxWidth, maxHeight)
    }

    /** Page count after dropping trailing empty pages, never below [minPages]. */
    fun pageCountAfterPrune(pageCount: Int, nonEmptyPages: Set<Int>, minPages: Int = 1): Int {
        val lastUsed = nonEmptyPages.maxOrNull() ?: -1
        return (lastUsed + 1).coerceIn(minPages, pageCount)
    }
}
