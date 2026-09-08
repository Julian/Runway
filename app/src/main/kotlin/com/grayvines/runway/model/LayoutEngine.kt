package com.grayvines.runway.model

/** Placement rules for one page. Pure Kotlin. */
object LayoutEngine {

    /** True if [footprint] is inside [grid] and overlaps no item. */
    fun canPlace(grid: GridSize, items: List<Placed>, footprint: Footprint): Boolean =
        footprint in grid && items.none { it.footprint.overlaps(footprint) }

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

    /**
     * Reordering within one row: moving the item at slot [from] to slot [to]. An empty target is a
     * plain move. An occupied target makes room: the run of occupied slots from [to] toward [from]
     * shifts one slot that way, up to the first gap (the vacated [from] counts as one). Returns the
     * new footprints of the shifted items; [items] must not include the moving item.
     */
    fun shiftFor(items: List<Placed>, from: Int, to: Int): Map<Long, Footprint> {
        if (from == to) return emptyMap()
        val bySlot = items.associateBy { it.footprint.x }
        val towardFrom = if (from < to) -1 else 1
        val moves = mutableMapOf<Long, Footprint>()
        var slot = to
        while (slot != from) {
            val occupant = bySlot[slot] ?: break // a gap: nothing beyond it needs to move
            moves[occupant.id] = occupant.footprint.copy(x = slot + towardFrom)
            slot += towardFrom
        }
        return moves
    }

    /** Page count after dropping trailing empty pages, never below [minPages]. */
    fun pageCountAfterPrune(pageCount: Int, nonEmptyPages: Set<Int>, minPages: Int = 1): Int {
        val lastUsed = nonEmptyPages.maxOrNull() ?: -1
        return (lastUsed + 1).coerceIn(minPages, pageCount)
    }
}
