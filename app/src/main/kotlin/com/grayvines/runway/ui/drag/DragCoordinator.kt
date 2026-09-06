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
    val homePageCount: Int

    /** Writes the move; false if it could not be saved. */
    suspend fun move(move: PendingMove): Boolean

    /** Returns once the observed layout shows [move] applied. */
    suspend fun awaitReflected(move: PendingMove)

    /** Adds a home page at [index] and returns once it is observable; false on failure. */
    suspend fun addHomePage(index: Int): Boolean
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

    private val _flipPage = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** Page delta requested by dwelling at an edge. */
    val flipPage: SharedFlow<Int> = _flipPage

    /** Where home and dock are on screen; a still finger is re-evaluated when that changes. */
    val areas = DropAreaTracker { drag.value?.let { dragTo(it.pointer) } }

    private val edgeDwell =
        EdgeDwell(
            scope,
            object : EdgeDwell.Actions {
                override fun isPastTheEnd(edge: Edge) =
                    edge == Edge.RIGHT && areas.areas.homePage >= workspace.homePageCount - 1

                override fun flip(delta: Int) {
                    _flipPage.tryEmit(delta)
                }

                override suspend fun addPage() = workspace.addHomePage(workspace.homePageCount)
            },
        )

    fun startDrag(source: DragSource, pointer: Point, grab: Point) =
        controller.start(source, pointer, grab)

    fun dragTo(pointer: Point) {
        val current = drag.value ?: return
        val target =
            areas.areas.targetFor(pointer, current.grab, current.source.spanX, current.source.spanY)
        val edge = areas.areas.edgeAt(pointer)
        edgeDwell.hover(edge)
        controller.move(pointer, target, edge)
    }

    /** Commits the planned drop, if any; the override shows it until the database catches up. */
    fun endDrag() {
        edgeDwell.stop()
        val state = drag.value ?: return
        val move = controller.drop() ?: return
        val from = Point(state.pointer.x - state.grab.x, state.pointer.y - state.grab.y)
        val pendingMove = move.asPendingMove(state.source.itemId).copy(from = from)
        _pending.value = pendingMove
        val settling = Settling(state.source.itemId, from)
        _settling.value = settling
        scope.launch {
            delay(SETTLE_TIMEOUT_MS) // safety net if the item never draws (e.g. off-screen page)
            if (_settling.value == settling) _settling.value = null
        }
        scope.launch {
            try {
                if (workspace.move(pendingMove)) workspace.awaitReflected(pendingMove)
            } finally {
                if (_pending.value == pendingMove) _pending.value = null
            }
        }
    }

    /** The UI finished animating [itemId] into its cell. */
    fun settled(itemId: Long) {
        if (_settling.value?.itemId == itemId) _settling.value = null
    }

    fun cancelDrag() {
        edgeDwell.stop()
        controller.cancel()
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
