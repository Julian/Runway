package com.grayvines.runway.ui.home

import com.grayvines.runway.data.Container
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.drag.DropPlan
import com.grayvines.runway.ui.drag.DropTarget
import com.grayvines.runway.ui.drag.isAt

/*
 * Where the carried item would land if let go now, for the page to outline: a move's target, or
 * the item's own cells when the finger is back over them (no move, but that is where it lands); a
 * fold, a refused drop, or no drag at all has none.
 */

/** The cells the carried item would take on home page [page] if let go now, or null. */
fun DragSession.plannedHomeFootprint(page: Int): Footprint? {
    val s = state ?: return null
    val target = s.target as? DropTarget.HomeCell ?: return null
    if (target.page != page) return null
    val own = s.source.isAt(Container.HOME, page, Footprint(target.x, target.y))
    if (s.plan !is DropPlan.Move && !own) return null
    return Footprint(target.x, target.y, s.source.spanX, s.source.spanY)
}

/** The slot the carried item would take on dock page [page] if let go now, or null. */
fun DragSession.plannedDockFootprint(page: Int): Footprint? {
    val s = state ?: return null
    val target = s.target as? DropTarget.DockSlot ?: return null
    if (target.page != page) return null
    val own = s.source.isAt(Container.DOCK, page, Footprint(target.slot, 0))
    if (s.plan !is DropPlan.Move && !own) return null
    return Footprint(target.slot, 0)
}
