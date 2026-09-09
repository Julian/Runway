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
    lookup: WorkspaceLookup,
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

    /** Where home and dock are on screen; a still finger is re-evaluated when that changes. */
    val areas = DropAreaTracker { drag.value?.let { dragTo(it.pointer) } }

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

    fun startDrag(source: DragSource, pointer: Point, grab: Point) {
        edgesArmed = false
        controller.start(source, pointer, grab)
    }

    fun dragTo(pointer: Point) {
        val current = drag.value ?: return
        val target =
            areas.areas.targetFor(pointer, current.grab, current.source.spanX, current.source.spanY)
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

    /** Commits the planned drop, if any; the override shows it until the database catches up. */
    fun endDrag() {
        edgeDwell.stop()
        resting?.cancel()
        val state = drag.value ?: return
        val plan = controller.drop()
        val from = Point(state.pointer.x - state.grab.x, state.pointer.y - state.grab.y)
        // Whether the drop lands or is refused, the icon settles from where it was released. An
        // app from the drawer has no cell to settle into or back to, and one folded away has no
        // cell of its own any more: those simply appear, or do not.
        val fresh = state.source.newApp != null || state.source.newFolder != null
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

    /** The UI finished animating [itemId] into its cell. */
    fun settled(itemId: Long) {
        if (_settling.value?.itemId == itemId) _settling.value = null
    }

    fun cancelDrag() {
        edgeDwell.stop()
        resting?.cancel()
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

        /** How long a finger rests on a cell before its neighbours slide aside. */
        const val REST_MS = 300L
    }
}
