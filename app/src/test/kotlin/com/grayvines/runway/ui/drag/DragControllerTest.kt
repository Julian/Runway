package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DragControllerTest {
    private val home =
        mutableMapOf(0 to listOf(Placed(1, Footprint(0, 0)), Placed(2, Footprint(1, 0))))
    private val dock = mutableMapOf(0 to listOf(Placed(9, Footprint(0, 0))))
    private val lookup =
        object : WorkspaceLookup {
            override val grid = GridSize(3, 2)
            override val dockSlots = 2

            override fun homeItems(page: Int) = home[page].orEmpty()

            override fun dockItems(page: Int) = dock[page].orEmpty()
        }
    private val controller = DragController(lookup)
    private val origin = Point(0f, 0f)

    private fun lift(id: Long, container: Container = Container.HOME, x: Int = 0, y: Int = 0) =
        controller.start(DragSource(id, ItemKind.APP, container, 0, x, y), origin, origin)

    @Test
    fun `free home cell moves without displacement`() {
        lift(1)
        controller.move(origin, DropTarget.HomeCell(0, 2, 1))
        assertEquals(
            DropPlan.Move(DropTarget.HomeCell(0, 2, 1), emptyMap()),
            controller.state.value?.plan,
        )
    }

    @Test
    fun `occupied home cell displaces the blocker`() {
        lift(1)
        controller.move(origin, DropTarget.HomeCell(0, 1, 0))
        val plan = controller.state.value?.plan as DropPlan.Move
        assertEquals(mapOf(2L to Footprint(0, 0)), plan.displaced) // into the vacated cell
    }

    @Test
    fun `dropping where it already is does nothing`() {
        lift(1)
        controller.move(origin, DropTarget.HomeCell(0, 0, 0))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
        assertNull(controller.drop())
    }

    @Test
    fun `empty dock slot accepts, occupied slot refuses`() {
        lift(1)
        controller.move(origin, DropTarget.DockSlot(0, 1))
        assertEquals(
            DropPlan.Move(DropTarget.DockSlot(0, 1), emptyMap()),
            controller.state.value?.plan,
        )
        controller.move(origin, DropTarget.DockSlot(0, 0))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
    }

    @Test
    fun `a dock item dropped on another dock slot reorders the row`() {
        dock[0] = listOf(Placed(9, Footprint(0, 0)), Placed(8, Footprint(1, 0)))
        lift(9, Container.DOCK)
        controller.move(origin, DropTarget.DockSlot(0, 1))
        assertEquals(
            DropPlan.Move(DropTarget.DockSlot(0, 1), mapOf(8L to Footprint(0, 0))),
            controller.state.value?.plan,
        )
    }

    @Test
    fun `an arrival on an occupied dock slot is still refused`() {
        lift(1) // from home
        controller.move(origin, DropTarget.DockSlot(0, 0))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
    }

    @Test
    fun `widgets never go in the dock`() {
        controller.start(DragSource(5, ItemKind.WIDGET, Container.HOME, 0, 2, 1), origin, origin)
        controller.move(origin, DropTarget.DockSlot(0, 1))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
    }

    @Test
    fun `drop returns the plan and clears the drag`() {
        lift(9, Container.DOCK)
        controller.move(origin, DropTarget.HomeCell(0, 2, 0))
        assertEquals(DropTarget.HomeCell(0, 2, 0), controller.drop()?.target)
        assertNull(controller.state.value)
    }

    @Test
    fun `no target means no plan`() {
        lift(1)
        controller.move(Point(5f, 5f), null)
        assertNull(controller.state.value?.plan)
        assertEquals(Point(5f, 5f), controller.state.value?.pointer)
    }
}
