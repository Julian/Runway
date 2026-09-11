package com.grayvines.runway.ui.home

import androidx.compose.runtime.mutableStateOf
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.drag.DragSource
import com.grayvines.runway.ui.drag.DragState
import com.grayvines.runway.ui.drag.DropPlan
import com.grayvines.runway.ui.drag.DropTarget
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.Settling
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DragSessionTest {
    private val drag = mutableStateOf<DragState?>(null)
    private val session =
        DragSession(
            stateOf = drag,
            settlingOf = mutableStateOf<Settling?>(null),
            settleTargetOf = mutableStateOf<Point?>(null),
            onHold = { _, _, _, _ -> },
            onStart = { _, _, _, _, _ -> },
            onStartNew = { _, _, _ -> },
            onMove = {},
            onEnd = {},
            onCancel = {},
        )
    private val widget =
        DragSource(7, ItemKind.WIDGET, Container.HOME, 0, 0, 0, spanX = 2, spanY = 1)

    private fun dragging(plan: DropPlan?, target: DropTarget? = plan.target()) {
        drag.value = DragState(widget, Point(0f, 0f), Point(0f, 0f), target = target, plan = plan)
    }

    private fun DropPlan?.target() =
        when (this) {
            is DropPlan.Move -> target
            is DropPlan.Fold -> target
            else -> null
        }

    @Test
    fun `back over its own cells the landing is those cells, though nothing would move`() {
        dragging(DropPlan.Invalid, target = DropTarget.HomeCell(page = 0, x = 0, y = 0))
        assertEquals(Footprint(0, 0, 2, 1), session.plannedHomeFootprint(0))
        dragging(DropPlan.Invalid, target = DropTarget.HomeCell(page = 0, x = 1, y = 0))
        assertNull(session.plannedHomeFootprint(0))
    }

    @Test
    fun `the planned landing is the target cell at the item's span, on that page only`() {
        dragging(DropPlan.Move(DropTarget.HomeCell(page = 1, x = 2, y = 1), emptyMap()))
        assertEquals(Footprint(2, 1, 2, 1), session.plannedHomeFootprint(1))
        assertNull(session.plannedHomeFootprint(0))
        assertNull(session.plannedDockFootprint(0))
    }

    @Test
    fun `a dock landing is its slot`() {
        dragging(DropPlan.Move(DropTarget.DockSlot(page = 0, slot = 3), emptyMap()))
        assertEquals(Footprint(3, 0), session.plannedDockFootprint(0))
        assertNull(session.plannedHomeFootprint(0))
    }

    @Test
    fun `nothing is outlined without a plan, for a fold, or when the drop would be refused`() {
        assertNull(session.plannedHomeFootprint(0))
        dragging(null)
        assertNull(session.plannedHomeFootprint(0))
        dragging(DropPlan.Fold(DropTarget.HomeCell(0, 1, 1), into = 9))
        assertNull(session.plannedHomeFootprint(0))
        dragging(DropPlan.Invalid)
        assertNull(session.plannedHomeFootprint(0))
    }
}
