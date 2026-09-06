package com.grayvines.runway.ui.drag

/**
 * Keeps [DropAreas] current from what the UI reports: every page's bounds as it is laid out, and
 * which page the pager has settled on. [onChange] runs whenever the areas change, so a still finger
 * can be re-evaluated against the page now under it.
 */
class DropAreaTracker(private val onChange: (DropAreas) -> Unit) {
    var areas = DropAreas()
        private set

    private val homeBounds = mutableMapOf<Int, Bounds>()
    private var shownHomePage = 0

    fun homePagePositioned(page: Int, bounds: Bounds, columns: Int, rows: Int) {
        homeBounds[page] = bounds
        if (page == shownHomePage) refreshHome(columns, rows)
    }

    fun homePageShown(page: Int, columns: Int, rows: Int) {
        shownHomePage = page
        refreshHome(columns, rows)
    }

    fun dockPagePositioned(page: Int, bounds: Bounds, slots: Int) {
        update(areas.copy(dock = bounds, dockPage = page, dockSlots = slots))
    }

    private fun refreshHome(columns: Int, rows: Int) {
        update(
            areas.copy(
                home = homeBounds[shownHomePage],
                homePage = shownHomePage,
                columns = columns,
                rows = rows,
            )
        )
    }

    private fun update(next: DropAreas) {
        if (next == areas) return
        areas = next
        onChange(next)
    }
}
