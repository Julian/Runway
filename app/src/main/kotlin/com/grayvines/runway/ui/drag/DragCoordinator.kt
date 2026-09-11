package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** A just-dropped item and where (root px, cell top-left) its settle animation starts. */
data class Settling(val itemId: Long, val from: Point)

/** What the coordinator needs from persistence. Implementations report failure, never throw. */
interface DragWorkspace {
    fun pageCount(container: Container): Int

    /** Writes the move (or, for a [PendingMove.newApp], the new placement); false if not saved. */
    suspend fun move(move: PendingMove): Boolean

    /** Returns once the observed layout shows [move] applied. */
    suspend fun awaitReflected(move: PendingMove)

    /**
     * Adds a page at [index] to [container] and returns once it is observable; false on failure.
     */
    suspend fun addPage(container: Container, index: Int): Boolean

    /** Removes trailing empty pages everywhere, so a page added by a dwell does not outlive it. */
    suspend fun pruneEmptyPages()
}

/**
 * One drag from lift to settled drop: the pointer and plan ([DragController]), where things are on
 * screen ([DropAreaTracker]), page flipping at edges ([EdgeDwell]), and the [pending] override
 * shown until the database reflects the drop. Pure Kotlin; time comes from [scope]'s clock.
 */
class DragCoordinator(
    private val scope: CoroutineScope,
    private val lookup: WorkspaceLookup,
    private val workspace: DragWorkspace,
) {
    private val controller = DragController(lookup)

    /** The drag in progress, if any. */
    val drag: StateFlow<DragState?> = controller.state

    private val _pending = MutableStateFlow<PendingMove?>(null)

    /** A committed drop the database has not reflected yet. */
    val pending: StateFlow<PendingMove?> = _pending

    private val _settling = MutableStateFlow<Settling?>(null)

    /**
     * The dropped item until its settle animation has run. Outlives [pending], which a fast write
     * can clear before the UI has even drawn a frame.
     */
    val settling: StateFlow<Settling?> = _settling

    private val _flipHomePage = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    private val _flipDockPage = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Page deltas requested by dwelling at a home or dock edge. */
    val flipHomePage: SharedFlow<Int> = _flipHomePage
    val flipDockPage: SharedFlow<Int> = _flipDockPage

    /**
     * Where home and dock are on screen; a still finger is re-evaluated when that changes, and a
     * release that was waiting for a page to settle lands once it has.
     */
    val areas = DropAreaTracker { areas ->
        drag.value?.let { follow(it.pointer) }
        if (released && areas.settled) drop()
    }

    /**
     * The finger has lifted but the drop is waiting for a scrolling page to settle: the bounds a
     * page reports mid-scroll would land the item in the wrong column, or on the wrong page.
     */
    private var released = false

    private val edgeDwell =
        EdgeDwell(
            scope,
            object : EdgeDwell.Actions {
                override fun isPastTheEnd(hover: EdgeHover): Boolean {
                    if (hover.edge != Edge.RIGHT) return false
                    val shown =
                        if (hover.container == Container.DOCK) {
                            areas.areas.dockPage
                        } else {
                            areas.areas.homePage
                        }
                    return shown >= workspace.pageCount(hover.container) - 1
                }

                override fun flip(container: Container, delta: Int) {
                    if (container == Container.DOCK) {
                        _flipDockPage.tryEmit(delta)
                    } else {
                        _flipHomePage.tryEmit(delta)
                    }
                }

                override suspend fun addPage(container: Container) =
                    workspace.addPage(container, workspace.pageCount(container))
            },
        )

    /**
     * Edges count only once the finger has been away from them during this drag: an item lifted
     * from an outer cell starts inside the edge zone, and must not flip pages by just being held.
     */
    private var edgesArmed = false

    /** Counts down while the finger stays on one target; done, neighbours slide aside. */
    private var resting: Job? = null

    /**
     * Begins a drag; a second finger's long press while one is live changes nothing. A lift while
     * the last drop is still settling ends that settle: the overlay carries the new item now, so
     * the settling one is simply in its cell, rather than hidden until the settle times out.
     */
    fun startDrag(source: DragSource, pointer: Point, grab: Point) {
        if (drag.value != null) return
        edgesArmed = false
        _settling.value = null
        controller.start(source, pointer, grab)
    }

    fun dragTo(pointer: Point) {
        if (released) return // the finger is up: nothing moves the item now
        follow(pointer)
    }

    /** Plans for the item at [pointer] against the areas as they are now. */
    private fun follow(pointer: Point) {
        val arrived = drag.value ?: return
        val target =
            areas.areas.targetFor(pointer, arrived.grab, arrived.source.spanX, arrived.source.spanY)
        pickUpIfPlaced(arrived, target)
        val current = drag.value ?: return
        val edge = areas.areas.edgeAt(pointer)
        if (edge == null) edgesArmed = true
        val hovered = edge.takeIf { edgesArmed }
        edgeDwell.hover(hovered)
        // Already folding: stay folding until the finger is nearly off the icon.
        val zone = if (current.plan is DropPlan.Fold) FOLD_KEEP_ZONE else FOLD_ZONE
        controller.move(pointer, target, hovered, over = areas.areas.cellUnder(pointer, zone))
        if (target != current.target) restOn(target)
    }

    /** A new target: neighbours hold still until the finger has rested there a moment. */
    private fun restOn(target: DropTarget?) {
        resting?.cancel()
        resting = target?.let {
            scope.launch {
                delay(REST_MS)
                controller.rested()
            }
        }
    }

    /**
     * The finger has lifted. The drop lands now, or, while a page flip is still scrolling under the
     * item, once the page has settled (and the still finger has been re-planned against it); a
     * pager that never reports settling is waited on for [SETTLE_WAIT_MS] at most.
     */
    fun endDrag() {
        edgeDwell.stop()
        resting?.cancel()
        if (drag.value == null) return
        if (areas.areas.settled) {
            drop()
        } else {
            released = true
            scope.launch {
                delay(SETTLE_WAIT_MS)
                if (released) drop()
            }
        }
    }

    /** Commits the planned drop, if any; the override shows it until the database catches up. */
    private fun drop() {
        released = false
        val state = drag.value ?: return
        val plan = controller.drop()
        val from = Point(state.pointer.x - state.grab.x, state.pointer.y - state.grab.y)
        // Whether the drop lands or is refused, the icon settles from where it was released. An
        // app from the drawer has no cell to settle into or back to, and one folded away has no
        // cell of its own any more: those simply appear, or do not.
        val source = state.source
        val fresh = source.newApp != null || source.newFolder != null || source.newWidget != null
        if (!fresh && plan !is DropPlan.Fold) {
            val settling = Settling(state.source.itemId, from)
            _settling.value = settling
            scope.launch {
                delay(
                    SETTLE_TIMEOUT_MS
                ) // safety net if the item never draws (e.g. off-screen page)
                if (_settling.value == settling) _settling.value = null
            }
        }
        if (plan == null) {
            showSourcePage(state.source)
            scope.launch { workspace.pruneEmptyPages() }
            return
        }
        val pendingMove =
            plan
                .asPendingMove(state.source.itemId)
                .copy(
                    from = from,
                    newApp = state.source.newApp,
                    fromFolder = state.source.fromFolder,
                    newFolder = state.source.newFolder,
                    newWidget = state.source.newWidget,
                    spanX = state.source.spanX,
                    spanY = state.source.spanY,
                )
        _pending.value = pendingMove
        scope.launch {
            try {
                if (workspace.move(pendingMove)) workspace.awaitReflected(pendingMove)
            } finally {
                if (_pending.value == pendingMove) _pending.value = null
            }
            workspace.pruneEmptyPages()
        }
    }

    /**
     * A drag of something with no cell yet (out of the drawer, or a folder) that reaches a page
     * already holding the same app or folder becomes a drag of that placement: it lifts out of its
     * cell into the finger, and the user is moving it. A page holds a thing once. An app being
     * taken out of a folder still is: the drop moves the placement and takes it out.
     */
    private fun pickUpIfPlaced(state: DragState, target: DropTarget?) {
        val source = state.source
        val identity = source.identity ?: return
        if (source.itemId != 0L || target == null) return
        val (container, page, items) =
            when (target) {
                is DropTarget.HomeCell ->
                    Triple(Container.HOME, target.page, lookup.homeItems(target.page))
                is DropTarget.DockSlot ->
                    Triple(Container.DOCK, target.page, lookup.dockItems(target.page))
            }
        val placed = items.firstOrNull { it.identity == identity } ?: return
        val from = areas.areas.centreOf(container, placed.footprint) ?: return
        val f = placed.footprint
        controller.adopt(
            DragSource(
                placed.id,
                source.kind,
                container,
                page,
                f.x,
                f.y,
                f.width,
                f.height,
                fromFolder = source.fromFolder,
                identity = identity,
            ),
            from,
        )
    }

    /**
     * A refused or cancelled drag settles the icon back into its cell, which the cell reports once
     * it is on screen. After a page flip the source page is not: flip back to it, so the icon is
     * seen to return rather than left hanging until the settle times out.
     */
    private fun showSourcePage(source: DragSource) {
        val shown =
            when (source.container) {
                Container.HOME -> areas.areas.homePage
                Container.DOCK -> areas.areas.dockPage
                Container.DRAWER -> return
            }
        val delta = source.page - shown
        if (delta == 0) return
        if (source.container == Container.DOCK) {
            _flipDockPage.tryEmit(delta)
        } else {
            _flipHomePage.tryEmit(delta)
        }
    }

    /** The UI finished animating [itemId] into its cell. */
    fun settled(itemId: Long) {
        if (_settling.value?.itemId == itemId) _settling.value = null
    }

    fun cancelDrag() {
        edgeDwell.stop()
        resting?.cancel()
        released = false
        drag.value?.let { showSourcePage(it.source) }
        controller.cancel()
        scope.launch { workspace.pruneEmptyPages() }
    }

    private fun DropPlan.asPendingMove(itemId: Long): PendingMove {
        val (target, displaced, into) =
            when (this) {
                is DropPlan.Move -> Triple(target, displaced, null)
                is DropPlan.Fold -> Triple(target, emptyMap(), into)
                DropPlan.Invalid -> error("an invalid plan is never dropped")
            }
        return when (target) {
            is DropTarget.HomeCell ->
                PendingMove(itemId, Container.HOME, target.page, target.x, target.y, displaced)
            is DropTarget.DockSlot ->
                PendingMove(itemId, Container.DOCK, target.page, target.slot, 0, displaced)
        }.copy(foldInto = into)
    }

    companion object {
        private const val SETTLE_TIMEOUT_MS = 2_000L

        /**
         * Longer than a flip's scroll (250 ms) by a margin: a page that never settles still drops.
         */
        const val SETTLE_WAIT_MS = 600L

        /** How long a finger rests on a cell before its neighbours slide aside. */
        const val REST_MS = 300L
    }
}
