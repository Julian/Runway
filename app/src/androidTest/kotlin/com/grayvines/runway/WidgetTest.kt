package com.grayvines.runway

import android.content.ComponentName
import android.graphics.Rect
import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A widget stored in the layout is drawn by the system's host view over the cells it spans. */
@RunWith(AndroidJUnit4::class)
class WidgetTest : LauncherFixture() {
    private val provider =
        ComponentName("com.grayvines.runway.fixture", "com.grayvines.runway.fixture.FixtureWidget")

    @Test
    fun aStoredWidgetIsDrawnOverItsCellsAndNoOthers() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        placeFixtureWidget()
        awaitWidgetCell()
        val widget = device.findObject(By.text(FIXTURE_WIDGET_TEXT))
        assertNotNull("the widget's own view is not on screen", widget)
        val bounds = widget.visibleBounds
        assertTrue("not over its first cell: $bounds", bounds.covers(grid.homeCell(0, 0)))
        assertTrue("not over its second cell: $bounds", bounds.covers(grid.homeCell(1, 0)))
        assertFalse("spills into the cell beside: $bounds", bounds.covers(grid.homeCell(2, 0)))
        assertFalse("spills into the cell below: $bounds", bounds.covers(grid.homeCell(0, 1)))
    }

    @Test
    fun theIconsAroundAWidgetStayWhereTheyWere() {
        placeFixtureWidget()
        awaitWidgetCell()
        // The first home cell held our settings app; the widget took its cell and the next, and
        // the rest of the row is as it was.
        val third = runBlocking {
            graph.workspace.observe(Container.HOME).first().pages.first().items.first {
                it.x == 2 && it.y == 0
            }
        }
        val label = apps.first { it.ref.component == third.component }.label
        val centre = cellIcon(label).fetchSemanticsNode().boundsInRoot.center
        val cell = Grid(settings.columns, settings.pageRows, settings.dockSlots).homeCell(2, 0)
        assertTrue(
            "$label drawn at $centre, not in its cell at $cell",
            (centre - cell).getDistance() < 2f,
        )
    }

    @Test
    fun aWidgetIsDrawnAgainWhenTheLauncherIsRecreated() {
        placeFixtureWidget()
        awaitWidgetCell()
        // Nothing is re-bound: the id is the host's, and a new view for it is all that is needed.
        compose.activityRule.scenario.recreate()
        awaitWidgetCell()
        assertTrue(device.hasObject(By.text(FIXTURE_WIDGET_TEXT)))
    }

    @Test
    fun aWidgetWhosePlacementIsRemovedLeavesTheScreen() {
        val itemId = placeFixtureWidget()
        awaitWidgetCell()
        assertTrue(device.hasObject(By.text(FIXTURE_WIDGET_TEXT)))
        runBlocking { graph.workspace.removeItem(itemId) }
        awaitGone(WIDGET_TAG)
        assertFalse(
            "the widget's view is still on screen",
            device.hasObject(By.text(FIXTURE_WIDGET_TEXT)),
        )
    }

    /**
     * Binds the fixture's widget the way the picker will, and puts it over the first two cells of
     * the first page, whose icons make way. The bind right is granted through the shell, as the
     * system's bind dialog would grant it.
     */
    private fun placeFixtureWidget(): Long {
        // By number: the appwidget command's "current" is refused by the service.
        val user = device.executeShellCommand("am get-current-user").trim()
        device.executeShellCommand("appwidget grantbind --package ${app.packageName} --user $user")
        val id = graph.widgets.allocateId()
        assertTrue(
            "could not bind the fixture widget",
            graph.widgets.bind(id, provider, Process.myUserHandle()),
        )
        return runBlocking {
            val page = graph.workspace.observe(Container.HOME).first().pages.first()
            page.items
                .filter { it.y == 0 && it.x in 0..1 }
                .forEach { graph.workspace.removeItem(it.id) }
            graph.workspace.addWidget(id, provider.flattenToString(), 0, 0, 0, spanX = 2, spanY = 1)
        }
    }

    /**
     * Waits for the widget's cell to compose. Through Compose, not UiAutomator: the composition's
     * frames only advance while the test drives them, so a UiAutomator wait alone would sit on a
     * screen that never changes.
     */
    private fun awaitWidgetCell() {
        waitUntil(LONG_TIMEOUT_MS) {
            compose.onAllNodesWithTag(WIDGET_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun Rect.covers(p: Offset) = contains(p.x.toInt(), p.y.toInt())

    private companion object {
        const val FIXTURE_WIDGET_TEXT = "Fixture widget"
    }
}
