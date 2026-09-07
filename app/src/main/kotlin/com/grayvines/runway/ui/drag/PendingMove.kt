package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.model.Footprint

/**
 * A drop that has been committed but not yet reflected by the database. The UI applies it in the
 * meantime so the dropped icon settles once, rather than snapping back and then jumping.
 */
data class PendingMove(
    val itemId: Long,
    val container: Container,
    val page: Int,
    val x: Int,
    val y: Int,
    val displaced: Map<Long, Footprint>,
    /** Root-pixel top-left of the lifted cell at release; the settle animation starts there. */
    val from: Point? = null,
    /** Set when the drop adds this app as a new item rather than moving [itemId]. */
    val newApp: AppRef? = null,
    /** Set when the drop folds the app into the item of this id at the target cell. */
    val foldInto: Long? = null,
)
