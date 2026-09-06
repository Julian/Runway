package com.grayvines.runway.data.settings

/** User configuration. The defaults are the product. */
data class Settings(
    val columns: Int = DEFAULT_COLUMNS,
    val rows: Int = DEFAULT_ROWS,
    val dockSlots: Int = DEFAULT_DOCK_SLOTS,
    val homeLabels: Boolean = false,
    val dockLabels: Boolean = false,
    val drawerLabels: Boolean = true,
    val searchBarAtTop: Boolean = true,
    /** Package that receives the web-search intent; null picks Firefox, else the first handler. */
    val searchTarget: String? = null,
) {
    /** Rows left for items: the dock and the search bar each take one full row. */
    val pageRows: Int
        get() = rows - RESERVED_ROWS

    companion object {
        const val DEFAULT_COLUMNS = 7
        const val DEFAULT_ROWS = 10
        const val DEFAULT_DOCK_SLOTS = 6
        const val RESERVED_ROWS = 2
        const val MIN_COLUMNS = 3
        const val MAX_COLUMNS = 12
        const val MIN_ROWS = RESERVED_ROWS + 2
        const val MAX_ROWS = 16
        const val MIN_DOCK_SLOTS = 1
        const val MAX_DOCK_SLOTS = 10
    }
}
