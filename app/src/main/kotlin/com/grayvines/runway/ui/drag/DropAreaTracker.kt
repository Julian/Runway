package com.grayvines.runway.ui.drag

/**
 * Keeps [DropAreas] current from what the UI reports: every page's bounds as it is laid out, and
 * which page each pager has settled on. [onChange] runs whenever the areas change, so a still
 * finger can be re-evaluated against the page now under it.
 */
class DropAreaTracker(private val onChange: (DropAreas) -> Unit) {
    var areas = DropAreas()
        private set

    private val home = Pages()
    private val dock = Pages()

    fun homePagePositioned(page: Int, bounds: Bounds, columns: Int, rows: Int) {
        if (home.positioned(page, bounds)) refreshHome(columns, rows)
    }

    fun homePageShown(page: Int, columns: Int, rows: Int) {
        home.shown = page
        refreshHome(columns, rows)
    }

    fun dockPagePositioned(page: Int, bounds: Bounds, slots: Int) {
        if (dock.positioned(page, bounds)) refreshDock(slots)
    }

    fun dockPageShown(page: Int, slots: Int) {
        dock.shown = page
        refreshDock(slots)
    }

    private fun refreshHome(columns: Int, rows: Int) =
        update(
            areas.copy(home = home.bounds, homePage = home.shown, columns = columns, rows = rows)
        )

    private fun refreshDock(slots: Int) =
        update(areas.copy(dock = dock.bounds, dockPage = dock.shown, dockSlots = slots))

    private fun update(next: DropAreas) {
        if (next == areas) return
        areas = next
        onChange(next)
    }

    /** One pager's reported page bounds and settled page. */
    private class Pages {
        private val byPage = mutableMapOf<Int, Bounds>()
        var shown = 0
        val bounds: Bounds?
            get() = byPage[shown]

        /** Records [bounds]; true if that is the shown page, so the areas need refreshing. */
        fun positioned(page: Int, bounds: Bounds): Boolean {
            byPage[page] = bounds
            return page == shown
        }
    }
}
