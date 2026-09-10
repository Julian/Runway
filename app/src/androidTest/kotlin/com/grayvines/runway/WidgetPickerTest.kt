package com.grayvines.runway

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.ui.menu.HOME_MENU_TAG
import com.grayvines.runway.ui.widgets.WIDGET_LIST_TAG
import com.grayvines.runway.ui.widgets.WIDGET_PICKER_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun whileBindingIsNotAllowedNothingIsAddedAndTheUserIsTold() {
        allowWidgetBinding(false)
        clearHomeCells(0 to 0, 1 to 0)
        openPicker()
        expectToast("Runway may not add widgets yet") { fixtureChoice().performClick() }
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
    private fun openPicker() {
        compose.onNode(hasContentDescription("Runway menu")).performClick()
        waitUntil { compose.onAllNodesWithTag(HOME_MENU_TAG).fetchSemanticsNodes().isNotEmpty() }
        menuRow("Widgets").performClick()
        // The list is there once the providers are read; its rows compose as they scroll in.
        waitUntil(LONG_TIMEOUT_MS) {
            compose.onAllNodesWithTag(WIDGET_LIST_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(WIDGET_LIST_TAG).performScrollToNode(hasText("Fixture widget"))
    }

    private fun fixtureChoice() =
        compose.onNode(hasText("Fixture widget") and hasAnyAncestor(hasTestTag(WIDGET_PICKER_TAG)))

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
}
