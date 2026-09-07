package com.grayvines.runway.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import com.grayvines.runway.data.Container
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DragState
import com.grayvines.runway.ui.drag.DropPlan
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.Settling

/**
 * The UI's view of dragging: what is lifted, where things would land, and how to report input.
 * Built once and kept: it reads the live drag through [State] objects, so a composable that reads
 * only what it needs (this cell lifted? this cell displaced?) recomposes only when that changes,
 * not on every move of the finger.
 */
@Stable
class DragSession(
    private val stateOf: State<DragState?>,
    private val settlingOf: State<Settling?>,
    /** Root-pixel centre of the cell a settling item belongs to, once that cell reports it. */
    private val settleTargetOf: State<Point?>,
    val onSettleTargetPositioned: (Point) -> Unit = {},
    val onSettled: (itemId: Long) -> Unit = {},
    private val onHold: (HomeItem, Container, Int, Bounds) -> Unit,
    private val onStart: (HomeItem, Container, Int, Point, Point) -> Unit,
    private val onStartFromDrawer: (AppEntry, Point, Point) -> Unit,
    private val onMove: (Point) -> Unit,
    private val onEnd: () -> Unit,
    private val onCancel: () -> Unit,
) {
    val state: DragState?
        get() = stateOf.value

    val settling: Settling?
        get() = settlingOf.value

    val settleTarget: Point?
        get() = settleTargetOf.value

    val draggedId: Long?
        get() = state?.source?.itemId

    /** The item the dragged app would fold into if let go now. */
    val foldTargetId: Long?
        get() = (state?.plan as? DropPlan.Fold)?.into

    /** Where a displaced item is previewed, once the finger has rested on its target. */
    fun previewFor(id: Long): Footprint? =
        state?.takeIf { it.rested }?.let { (it.plan as? DropPlan.Move)?.displaced?.get(id) }

    /** Pointer tracking after a start comes from the root ([tracksDrag]), not the cell. */
    fun move(pointer: Point) = onMove(pointer)

    fun end() = onEnd()

    fun cancel() = onCancel()

    /** Drawer apps have no menu on hold; moving after the hold pulls a new placement out. */
    fun handlersForDrawer(app: AppEntry) =
        DragHandlers(
            onHold = {},
            onStart = { pointer, grab -> onStartFromDrawer(app, pointer, grab) },
        )

    fun handlersFor(item: HomeItem, page: Int, container: Container = Container.HOME) =
        DragHandlers(
            onHold = { cell -> onHold(item, container, page, cell) },
            onStart = { pointer, grab -> onStart(item, container, page, pointer, grab) },
        )
}
