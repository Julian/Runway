package com.grayvines.runway.ui.home

import com.grayvines.runway.data.Container
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.drag.DragState
import com.grayvines.runway.ui.drag.DropPlan
import com.grayvines.runway.ui.drag.Point

/** The UI's view of dragging: what is lifted, where things would land, and how to report input. */
class DragSession(
    val state: DragState?,
    private val onStart: (HomeItem, Container, Int, Point, Point) -> Unit,
    private val onMove: (Point) -> Unit,
    private val onEnd: () -> Unit,
    private val onCancel: () -> Unit,
) {
    val draggedId: Long?
        get() = state?.source?.itemId

    /** Where a displaced item is previewed while the drag hovers. */
    fun previewFor(id: Long): Footprint? = (state?.plan as? DropPlan.Move)?.displaced?.get(id)

    fun handlersFor(item: HomeItem, page: Int, container: Container = Container.HOME) =
        DragHandlers(
            onStart = { pointer, grab -> onStart(item, container, page, pointer, grab) },
            onMove = onMove,
            onEnd = onEnd,
            onCancel = onCancel,
        )
}
