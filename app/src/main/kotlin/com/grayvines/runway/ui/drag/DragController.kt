package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.LayoutEngine
import com.grayvines.runway.model.Placed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Where a dragged item came from. */
data class DragSource(
    val itemId: Long,
    val kind: ItemKind,
    val container: Container,
    val page: Int,
    val x: Int,
    val y: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
)

sealed interface DropTarget {
    data class HomeCell(val page: Int, val x: Int, val y: Int) : DropTarget

    data class DockSlot(val page: Int, val slot: Int) : DropTarget
}

/** What a drop at the hovered target would do. */
sealed interface DropPlan {
    data class Move(val target: DropTarget, val displaced: Map<Long, Footprint>) : DropPlan

    data object Invalid : DropPlan
}

/** A point in root pixel coordinates. */
data class Point(val x: Float, val y: Float)

data class DragState(
    val source: DragSource,
    /** Pointer position, root pixels. */
    val pointer: Point,
    /** Where inside the item the pointer grabbed it, pixels. */
    val grab: Point,
    val target: DropTarget? = null,
    val plan: DropPlan? = null,
    /** The edge being hovered, which flips pages after a dwell. */
    val edge: EdgeHover? = null,
)

/** What the controller needs to know about the workspace to plan a drop. */
interface WorkspaceLookup {
    val grid: GridSize
    val dockSlots: Int

    fun homeItems(page: Int): List<Placed>

    fun dockItems(page: Int): List<Placed>
}

/** One drag at a time: tracks the pointer and plans the drop. Pure Kotlin. */
class DragController(private val lookup: WorkspaceLookup) {
    private val _state = MutableStateFlow<DragState?>(null)
    val state: StateFlow<DragState?> = _state

    fun start(source: DragSource, pointer: Point, grab: Point) {
        _state.value = DragState(source, pointer, grab)
    }

    fun move(pointer: Point, target: DropTarget?, edge: EdgeHover? = null) {
        val current = _state.value ?: return
        val plan = target?.let { plan(current.source, it) }
        _state.value = current.copy(pointer = pointer, target = target, plan = plan, edge = edge)
    }

    /** Ends the drag; the move to apply, or null if nothing changes. */
    fun drop(): DropPlan.Move? {
        val plan = _state.value?.plan
        _state.value = null
        return plan as? DropPlan.Move
    }

    fun cancel() {
        _state.value = null
    }

    private fun plan(source: DragSource, target: DropTarget): DropPlan =
        when (target) {
            is DropTarget.HomeCell -> planHome(source, target)
            is DropTarget.DockSlot -> planDock(source, target)
        }

    private fun planHome(source: DragSource, target: DropTarget.HomeCell): DropPlan {
        val footprint = Footprint(target.x, target.y, source.spanX, source.spanY)
        if (source.isAt(Container.HOME, target.page, footprint)) return DropPlan.Invalid
        val others = lookup.homeItems(target.page).filter { it.id != source.itemId }
        val displaced =
            LayoutEngine.displaceFor(lookup.grid, others, source.itemId, footprint)
                ?: return DropPlan.Invalid
        return DropPlan.Move(target, displaced)
    }

    /**
     * Within a dock page a move reorders (slots between shift toward the vacated one). Arrivals
     * from elsewhere never displace: an occupied slot is refused (folders come later).
     */
    private fun planDock(source: DragSource, target: DropTarget.DockSlot): DropPlan {
        if (source.kind == ItemKind.WIDGET) return DropPlan.Invalid
        if (target.slot !in 0 until lookup.dockSlots) return DropPlan.Invalid
        if (source.isAt(Container.DOCK, target.page, Footprint(target.slot, 0))) {
            return DropPlan.Invalid
        }
        val others = lookup.dockItems(target.page).filter { it.id != source.itemId }
        val reordering = source.container == Container.DOCK && source.page == target.page
        return when {
            reordering ->
                DropPlan.Move(target, LayoutEngine.shiftFor(others, source.x, target.slot))
            others.any { it.footprint.x == target.slot } -> DropPlan.Invalid
            else -> DropPlan.Move(target, emptyMap())
        }
    }

    private fun DragSource.isAt(container: Container, page: Int, footprint: Footprint) =
        this.container == container && this.page == page && x == footprint.x && y == footprint.y
}
