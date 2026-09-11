package com.grayvines.runway

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.menu.HOME_MENU_TAG
import com.grayvines.runway.ui.widgets.WIDGET_LIST_TAG
import com.grayvines.runway.ui.widgets.WIDGET_PICKER_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Widgets" on the home menu lists the device's widgets; tapping one puts it on the page being
 * shown, at the size it was designed for when there is room, at its smallest otherwise.
 */
@RunWith(AndroidJUnit4::class)
class WidgetPickerTest : LauncherFixture() {
    @Test
    fun widgetsOnTheHomeMenuListsTheDevicesWidgetsByAppAndBackClosesTheList() {
        openPicker()
        fixtureChoice().assertIsDisplayed()
        compose
            .onNode(hasText("Fixture") and hasAnyAncestor(hasTestTag(WIDGET_PICKER_TAG)))
            .assertIsDisplayed()
        device.pressBack()
        awaitGone(WIDGET_PICKER_TAG)
        assertStillOnLauncher()
    }

    @Test
    fun aChosenWidgetLandsOnTheShownPageAtItsDesignedSize() {
        allowWidgetBinding(true)
        clearHomeCells(0 to 0, 1 to 0)
        openPicker()
        fixtureChoice().performClick()
        awaitGone(WIDGET_PICKER_TAG)
        val widget = awaitPlacedWidget()
        assertEquals(0 to 0, widget.x to widget.y)
        assertEquals(2 to 1, widget.spanX to widget.spanY)
        waitUntil { compose.onAllNodesWithTag(WIDGET_TAG).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun withNoRoomForItsDesignedSizeAWidgetTakesItsSmallest() {
        allowWidgetBinding(true)
        clearHomeCells(0 to 0)
        openPicker()
        fixtureChoice().performClick()
        val widget = awaitPlacedWidget()
        assertEquals(0 to 0, widget.x to widget.y)
        assertEquals(1 to 1, widget.spanX to widget.spanY)
    }

    @Test
    fun withNoRoomAtAllNothingIsAddedAndTheUserIsTold() {
        allowWidgetBinding(true)
        openPicker() // the first page is full by default
        expectToast("No room on this page") { fixtureChoice().performClick() }
        assertEquals(emptyList<ItemEntity>(), placedWidgets())
    }

    @Test
    fun theFirstWidgetAsksTheSystemsLeaveToBind_andAllowingItPlacesTheWidget() {
        allowWidgetBinding(false)
        clearHomeCells(0 to 0, 1 to 0)
        openPicker()
        fixtureChoice().performClick()
        val allow = device.wait(Until.findObject(BIND_ALLOW), LONG_TIMEOUT_MS)
        assertNotNull("the system never asked whether Runway may bind widgets", allow)
        // Ticked, the dialog grants Runway for good; unticked it binds this one widget only.
        device.findObject(BIND_ALWAYS).click()
        allow.click()
        val widget = awaitPlacedWidget()
        assertEquals(0 to 0, widget.x to widget.y)
        // Granted for good: the next widget goes straight in.
        runBlocking { graph.workspace.removeItem(widget.id) }
        awaitGone(WIDGET_TAG)
        openPicker()
        fixtureChoice().performClick()
        awaitPlacedWidget()
        assertFalse(device.hasObject(BIND_ALLOW))
    }

    @Test
    fun decliningTheSystemsLeaveToBindAddsNothing() {
        allowWidgetBinding(false)
        clearHomeCells(0 to 0, 1 to 0)
        openPicker()
        fixtureChoice().performClick()
        val cancel = device.wait(Until.findObject(BIND_CANCEL), LONG_TIMEOUT_MS)
        assertNotNull("the system never asked whether Runway may bind widgets", cancel)
        cancel.click()
        // The notice's toast is shown as the launcher comes back, before accessibility hears of
        // it; what matters is checked instead: nothing was added.
        waitUntil(LONG_TIMEOUT_MS) { device.currentPackageName == app.packageName }
        SystemClock.sleep(WRITE_GRACE_MS)
        assertEquals(emptyList<ItemEntity>(), placedWidgets())
    }

    @Test
    fun aWidgetThatInsistsOnSetupRunsItsSetupScreen_andIsPlacedWhenThatIsDone() {
        allowWidgetBinding(true)
        clearHomeCells(0 to 0, 1 to 0)
        openPicker(SETUP_WIDGET)
        choice(SETUP_WIDGET).performClick()
        val done = device.wait(Until.findObject(By.text("Done")), LONG_TIMEOUT_MS)
        assertNotNull("the widget's setup screen never opened", done)
        done.click()
        val widget = awaitPlacedWidget()
        assertEquals(0 to 0, widget.x to widget.y)
        assertTrue(widget.provider!!.endsWith("FixtureSetupWidget"))
    }

    @Test
    fun cancellingAWidgetsSetupScreenAddsNothing() {
        allowWidgetBinding(true)
        clearHomeCells(0 to 0, 1 to 0)
        openPicker(SETUP_WIDGET)
        choice(SETUP_WIDGET).performClick()
        val cancel = device.wait(Until.findObject(By.text("Cancel")), LONG_TIMEOUT_MS)
        assertNotNull("the widget's setup screen never opened", cancel)
        cancel.click()
        waitUntil(LONG_TIMEOUT_MS) { device.currentPackageName == app.packageName }
        SystemClock.sleep(WRITE_GRACE_MS)
        assertEquals(emptyList<ItemEntity>(), placedWidgets())
    }

    @Test
    fun aWidgetDraggedOutOfThePickerLandsWhereItIsDroppedAtItsDesignedSize() {
        allowWidgetBinding(true)
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        clearHomeCells(0 to 2, 1 to 2)
        openPicker()
        liftFromPicker()
        awaitGone(WIDGET_PICKER_TAG) // the picker closes under the finger
        val carried =
            compose
                .onNodeWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "carried at ${carried.width} wide, not two cells",
            abs(carried.width - grid.cellWidth() * 2) < grid.cellWidth() * 0.15f,
        )
        dragOn((grid.homeCell(0, 2) + grid.homeCell(1, 2)) / 2f)
        release()
        val widget = awaitPlacedWidget()
        assertEquals(0 to 2, widget.x to widget.y)
        assertEquals(2 to 1, widget.spanX to widget.spanY)
    }

    @Test
    fun aWidgetDraggedOutOfThePickerOntoIconsPushesThemAside() {
        allowWidgetBinding(true)
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        clearHomeCells(2 to 2, 3 to 2)
        val pushed = listOf(labelAtHomeCell(0, 1), labelAtHomeCell(1, 1))
        openPicker()
        liftFromPicker()
        dragOn((grid.homeCell(0, 1) + grid.homeCell(1, 1)) / 2f)
        release()
        val widget = awaitPlacedWidget()
        assertEquals(0 to 1, widget.x to widget.y)
        pushed.forEach { label ->
            waitUntil { homeCellOf(label).let { it != null && it != 0 to 1 && it != 1 to 1 } }
        }
    }

    @Test
    fun aWidgetDraggedOutOfThePickerAndDroppedOnTheDockAddsNothing() {
        allowWidgetBinding(true)
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        openPicker()
        liftFromPicker()
        dragOn(grid.dockSlot(1))
        release()
        awaitGone(DRAG_OVERLAY_TAG)
        assertEquals(emptyList<ItemEntity>(), placedWidgets())
    }

    @Test
    fun theHomeIntentClosesThePicker() {
        openPicker()
        sendHomeIntent()
        awaitGone(WIDGET_PICKER_TAG)
        assertStillOnLauncher()
    }

    /** Through the search bar's three dots: the pages may be full, with nowhere to long-press. */
    private fun openPicker(label: String = FIXTURE_WIDGET_LABEL) {
        compose.onNode(hasContentDescription("Runway menu")).performClick()
        waitUntil { compose.onAllNodesWithTag(HOME_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
        menuRow("Widgets").performClick()
        // The list is there once the providers are read; its rows compose as they scroll in.
        waitUntil(LONG_TIMEOUT_MS) {
            compose.onAllNodesWithTag(WIDGET_LIST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(WIDGET_LIST_TAG).performScrollToNode(hasText(label))
    }

    /** Long-presses the fixture widget's row and nudges it, so the drag has begun. */
    private fun liftFromPicker() {
        val start = fixtureChoice().fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        compose.onRoot().performTouchInput { moveBy(Offset(0f, -LIFT_NUDGE_PX)) }
        waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun fixtureChoice() = choice(FIXTURE_WIDGET_LABEL)

    private fun choice(label: String) =
        compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag(WIDGET_PICKER_TAG)))

    /** Empties [cells] of the first page and waits for the screen to show them empty. */
    private fun clearHomeCells(vararg cells: Pair<Int, Int>) {
        val removed = runBlocking {
            val page = graph.workspace.observe(Container.HOME).first().pages.first()
            page.items
                .filter { it.x to it.y in cells }
                .onEach { graph.workspace.removeItem(it.id) }
                .map { item -> apps.first { it.ref.component == item.component }.label }
        }
        waitUntil { removed.none { icon(it).isDisplayedOrFalse() } }
    }

    private fun placedWidgets(): List<ItemEntity> = runBlocking {
        graph.workspace.observe(Container.HOME).first().pages.first().items.filter {
            it.kind == ItemKind.WIDGET
        }
    }

    private fun awaitPlacedWidget(): ItemEntity {
        waitUntil(LONG_TIMEOUT_MS) { placedWidgets().isNotEmpty() }
        return placedWidgets().single()
    }

    private companion object {
        const val FIXTURE_WIDGET_LABEL = "Fixture widget"
        const val SETUP_WIDGET = "Fixture setup widget"

        /** The system's "may this launcher create widgets?" dialog, by its buttons' ids. */
        val BIND_ALLOW: BySelector = By.res("android", "button1")
        val BIND_CANCEL: BySelector = By.res("android", "button2")
        val BIND_ALWAYS: BySelector = By.res("android", "alwaysUse")
    }
}
