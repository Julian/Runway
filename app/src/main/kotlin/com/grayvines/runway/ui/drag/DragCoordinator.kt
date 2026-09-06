package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import kotlinx.coroutines.CoroutineScope
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

    /** Writes the move; false if it could not be saved. */
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
        controller.move(pointer, target, hovered)
    }

    /** Commits the planned drop, if any; the override shows it until the database catches up. */
    fun endDrag() {
        edgeDwell.stop()
        val state = drag.value ?: return
        val move = controller.drop()
        val from = Point(state.pointer.x - state.grab.x, state.pointer.y - state.grab.y)
        // Whether the drop lands or is refused, the icon settles from where it was released.
        val settling = Settling(state.source.itemId, from)
        _settling.value = settling
        scope.launch {
            delay(SETTLE_TIMEOUT_MS) // safety net if the item never draws (e.g. off-screen page)
            if (_settling.value == settling) _settling.value = null
        }
        if (move == null) {
            scope.launch { workspace.pruneEmptyPages() }
            return
        }
        val pendingMove = move.asPendingMove(state.source.itemId).copy(from = from)
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
        controller.cancel()
        scope.launch { workspace.pruneEmptyPages() }
    }

    private fun DropPlan.Move.asPendingMove(itemId: Long) =
        when (val t = target) {
            is DropTarget.HomeCell ->
                PendingMove(itemId, Container.HOME, t.page, t.x, t.y, displaced)
            is DropTarget.DockSlot ->
                PendingMove(itemId, Container.DOCK, t.page, t.slot, 0, displaced)
        }

    private companion object {
        const val SETTLE_TIMEOUT_MS = 2_000L
    }
}
