package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.LayoutEngine
import com.grayvines.runway.model.Placed
import com.grayvines.runway.model.overlaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Where a dragged item came from. An app pulled out of the drawer has no item yet: [newApp] says
 * which app, [itemId] is 0, and the drop adds a placement instead of moving one.
 */
data class DragSource(
    val itemId: Long,
    val kind: ItemKind,
    val container: Container,
    val page: Int,
    val x: Int,
    val y: Int,
    val spanX: Int = 1,
    val spanY: Int = 1,
    val newApp: AppRef? = null,
    /** Set with [newApp] when the app was lifted out of this folder rather than the drawer. */
    val fromFolder: Long? = null,
    /** A drawer folder lifted out of the drawer: the drop places it again, in a cell. */
    val newFolder: Long? = null,
    /** What this is a placement of, as [Placed.identity]; a page holding one refuses another. */
    val identity: String? = null,
)

sealed interface DropTarget {
    data class HomeCell(val page: Int, val x: Int, val y: Int) : DropTarget

    data class DockSlot(val page: Int, val slot: Int) : DropTarget
}

/** What a drop at the hovered target would do. */
sealed interface DropPlan {
    data class Move(val target: DropTarget, val displaced: Map<Long, Footprint>) : DropPlan

    /**
     * The dragged app goes into the item [into] at [target]: a folder, or an app that becomes one.
     */
    data class Fold(val target: DropTarget, val into: Long) : DropPlan

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
    /**
     * The finger has rested on [target] long enough for neighbours to make room on screen. The plan
     * applies on a drop regardless; this only holds the preview back while passing over.
     */
    val rested: Boolean = false,
    /**
     * Root-pixel centre of the cell this item was picked up from mid-drag, when a fresh drag met a
     * page that already held the same thing and took that placement over instead; the icon slides
     * from there into the finger.
     */
    val pickedUpFrom: Point? = null,
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

    /**
     * [target] is where the item's corner snaps; [over] is the cell the finger itself is well
     * inside, if any, which is where a dropped app folds with what is there.
     */
    /**
     * The drag becomes one of [source] instead, an item already placed, picked up out of its cell
     * at [from] (root px centre). The finger and its grab stay as they are.
     */
    fun adopt(source: DragSource, from: Point) {
        val current = _state.value ?: return
        _state.value = current.copy(source = source, pickedUpFrom = from, plan = null)
    }

    fun move(
        pointer: Point,
        target: DropTarget?,
        edge: EdgeHover? = null,
        over: DropTarget? = null,
    ) {
        val current = _state.value ?: return
        val plan =
            over?.let { planFold(current.source, it) } ?: target?.let { plan(current.source, it) }
        _state.value =
            current.copy(
                pointer = pointer,
                target = target,
                plan = plan,
                edge = edge,
                rested = current.rested && target == current.target,
            )
    }

    /** The finger has been on its target a while: neighbours may now be shown making room. */
    fun rested() {
        _state.value = _state.value?.copy(rested = true)
    }

    /** Ends the drag; the move or fold to apply, or null if nothing changes. */
    fun drop(): DropPlan? {
        val plan = _state.value?.plan
        _state.value = null
        return plan?.takeIf { it != DropPlan.Invalid }
    }

    fun cancel() {
        _state.value = null
    }

    /**
     * Only a single app folds, and only into an app or folder that is not itself. Within a dock
     * page, dropping on an icon reorders instead: that row is for reaching, not for filing.
     */
    private fun planFold(source: DragSource, over: DropTarget): DropPlan.Fold? {
        val reordering =
            over is DropTarget.DockSlot &&
                source.container == Container.DOCK &&
                source.page == over.page
        if (source.kind != ItemKind.APP || reordering) return null
        val (items, cell) =
            when (over) {
                is DropTarget.HomeCell -> lookup.homeItems(over.page) to Footprint(over.x, over.y)
                is DropTarget.DockSlot -> lookup.dockItems(over.page) to Footprint(over.slot, 0)
            }
        val there = items.firstOrNull {
            it.id != source.itemId &&
                it.foldable &&
                it.footprint.isSingleCell &&
                it.footprint.overlaps(cell)
        }
        return there?.let { DropPlan.Fold(over, it.id) }
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
        if (source.isAlreadyAmong(others)) return DropPlan.Invalid
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
            source.isAlreadyAmong(others) -> DropPlan.Invalid
            reordering ->
                DropPlan.Move(target, LayoutEngine.shiftFor(others, source.x, target.slot))
            others.any { it.footprint.x == target.slot } -> DropPlan.Invalid
            else -> DropPlan.Move(target, emptyMap())
        }
    }
}

/** The same app or folder is placed on that page already: once per page is enough. */
internal fun DragSource.isAlreadyAmong(others: List<Placed>) =
    identity != null && others.any { it.identity == identity }

internal fun DragSource.isAt(container: Container, page: Int, footprint: Footprint) =
    this.container == container && this.page == page && x == footprint.x && y == footprint.y
