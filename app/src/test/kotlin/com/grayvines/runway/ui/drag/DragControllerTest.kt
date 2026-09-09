package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.AppRef
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

    private fun lift(
        id: Long,
        container: Container = Container.HOME,
        x: Int = 0,
        y: Int = 0,
        identity: String? = null,
    ) =
        controller.start(
            DragSource(id, ItemKind.APP, container, 0, x, y, identity = identity),
            origin,
            origin,
        )

    @Test
    fun `a page that already holds the app refuses a second placement of it`() {
        val app = AppRef("a/.Main", 0)
        home[0] = listOf(Placed(1, Footprint(0, 0), identity = app.identity))
        home[1] = emptyList()
        dock[0] = listOf(Placed(9, Footprint(0, 0), identity = app.identity))
        val fromDrawer =
            DragSource(
                0,
                ItemKind.APP,
                Container.DRAWER,
                0,
                0,
                0,
                newApp = app,
                identity = app.identity,
            )
        controller.start(fromDrawer, origin, origin)

        controller.move(origin, DropTarget.HomeCell(0, 2, 1))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
        controller.move(origin, DropTarget.DockSlot(0, 1))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
        controller.move(origin, DropTarget.HomeCell(1, 0, 0)) // another page: fine
        assertEquals(
            DropPlan.Move(DropTarget.HomeCell(1, 0, 0), emptyMap()),
            controller.state.value?.plan,
        )
    }

    @Test
    fun `moving an item to a page that already holds the same app is refused`() {
        val app = AppRef("a/.Main", 0)
        home[0] = listOf(Placed(1, Footprint(0, 0), identity = app.identity))
        home[1] = listOf(Placed(3, Footprint(0, 0), identity = app.identity))
        lift(1, identity = app.identity)

        controller.move(origin, DropTarget.HomeCell(1, 2, 1))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
        controller.move(origin, DropTarget.HomeCell(0, 2, 1)) // its own page: a plain move
        assertEquals(
            DropPlan.Move(DropTarget.HomeCell(0, 2, 1), emptyMap()),
            controller.state.value?.plan,
        )
    }

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
        assertEquals(DropTarget.HomeCell(0, 2, 0), (controller.drop() as DropPlan.Move).target)
        assertNull(controller.state.value)
    }

    @Test
    fun `hovering well over another app plans a fold into it`() {
        lift(1)
        controller.move(origin, DropTarget.HomeCell(0, 1, 0), over = DropTarget.HomeCell(0, 1, 0))
        assertEquals(
            DropPlan.Fold(DropTarget.HomeCell(0, 1, 0), into = 2L),
            controller.state.value?.plan,
        )
        assertEquals(DropPlan.Fold(DropTarget.HomeCell(0, 1, 0), into = 2L), controller.drop())
    }

    @Test
    fun `over an empty cell, or its own cell, a drag plans as before`() {
        lift(1)
        controller.move(origin, DropTarget.HomeCell(0, 2, 0), over = DropTarget.HomeCell(0, 2, 0))
        assertEquals(
            DropPlan.Move(DropTarget.HomeCell(0, 2, 0), emptyMap()),
            controller.state.value?.plan,
        )
        controller.move(origin, DropTarget.HomeCell(0, 0, 0), over = DropTarget.HomeCell(0, 0, 0))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
    }

    @Test
    fun `an app folds into a dock icon, but nothing folds into a widget and a folder never folds`() {
        lift(1)
        controller.move(origin, DropTarget.DockSlot(0, 0), over = DropTarget.DockSlot(0, 0))
        assertEquals(
            DropPlan.Fold(DropTarget.DockSlot(0, 0), into = 9L),
            controller.state.value?.plan,
        )

        home[0] = listOf(Placed(1, Footprint(0, 0)), Placed(2, Footprint(1, 0), foldable = false))
        controller.move(origin, DropTarget.HomeCell(0, 1, 0), over = DropTarget.HomeCell(0, 1, 0))
        assertEquals(
            mapOf(2L to Footprint(0, 0)),
            (controller.state.value?.plan as DropPlan.Move).displaced,
        )

        controller.cancel()
        controller.start(DragSource(1, ItemKind.FOLDER, Container.HOME, 0, 0, 0), origin, origin)
        controller.move(origin, DropTarget.DockSlot(0, 0), over = DropTarget.DockSlot(0, 0))
        assertEquals(
            DropPlan.Invalid,
            controller.state.value?.plan,
        ) // an occupied slot refuses a move
    }

    @Test
    fun `within a dock page a drop on an icon reorders rather than folds`() {
        dock[0] = listOf(Placed(9, Footprint(0, 0)), Placed(10, Footprint(1, 0)))
        lift(9, Container.DOCK)
        controller.move(origin, DropTarget.DockSlot(0, 1), over = DropTarget.DockSlot(0, 1))
        assertEquals(
            mapOf(10L to Footprint(0, 0)),
            (controller.state.value?.plan as DropPlan.Move).displaced,
        )
    }

    @Test
    fun `rested holds only while the target stays the same`() {
        lift(1)
        controller.move(origin, DropTarget.HomeCell(0, 1, 0))
        assertEquals(false, controller.state.value?.rested)
        controller.rested()
        controller.move(Point(1f, 1f), DropTarget.HomeCell(0, 1, 0))
        assertEquals(true, controller.state.value?.rested) // same cell: still rested
        controller.move(origin, DropTarget.HomeCell(0, 2, 0))
        assertEquals(false, controller.state.value?.rested) // a new cell starts over
    }

    @Test
    fun `no target means no plan`() {
        lift(1)
        controller.move(Point(5f, 5f), null)
        assertNull(controller.state.value?.plan)
        assertEquals(Point(5f, 5f), controller.state.value?.pointer)
    }

    @Test
    fun `an app from the drawer lands on a free cell, displaces on an occupied one, never in a full dock slot`() {
        val fromDrawer =
            DragSource(0, ItemKind.APP, Container.DRAWER, 0, 0, 0, newApp = AppRef("new/.Main", 0))
        controller.start(fromDrawer, Point(0f, 0f), Point(0f, 0f))
        controller.move(Point(0f, 0f), DropTarget.HomeCell(0, 2, 1))
        assertEquals(
            DropPlan.Move(DropTarget.HomeCell(0, 2, 1), emptyMap()),
            controller.state.value?.plan,
        )
        controller.move(Point(0f, 0f), DropTarget.HomeCell(0, 0, 0))
        assertEquals(
            mapOf(1L to Footprint(2, 0)),
            (controller.state.value?.plan as DropPlan.Move).displaced,
        )
        controller.move(Point(0f, 0f), DropTarget.DockSlot(0, 0))
        assertEquals(DropPlan.Invalid, controller.state.value?.plan)
        controller.move(Point(0f, 0f), DropTarget.DockSlot(0, 1))
        assertEquals(
            DropPlan.Move(DropTarget.DockSlot(0, 1), emptyMap()),
            controller.state.value?.plan,
        )
    }
}
