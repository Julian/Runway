package com.grayvines.runway

import android.content.ComponentName
import android.graphics.Rect
import android.os.Process
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.addWidget
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import com.grayvines.runway.ui.shade.SHADE_HINT_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun aSwipeAcrossAWidgetFlipsThePage() {
        placeFixtureWidget()
        awaitWidgetCell()
        val onPageTwo = labelOnPage(1)
        val from = widgetCentre()
        val pageWidth = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().size.width
        compose.onRoot().performTouchInput {
            swipe(from, from - Offset(pageWidth * 0.6f, 0f), durationMillis = 200)
        }
        compose.waitForIdle()
        icon(onPageTwo).assertIsDisplayed()
    }

    @Test
    fun aSwipeUpOnAWidgetLeavesTheDrawerClosed() {
        placeFixtureWidget()
        awaitWidgetCell()
        val height = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        pullFrom(widgetCentre(), -height * OPENING_PULL)
        compose.onAllNodesWithTag(DRAWER_TAG).assertCountEquals(0)
        release()
        compose.waitForIdle()
        compose.onAllNodesWithTag(DRAWER_TAG).assertCountEquals(0)
    }

    @Test
    fun aSwipeDownOnAWidgetLeavesTheShadeAlone() {
        placeFixtureWidget()
        awaitWidgetCell()
        val height = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        pullFrom(widgetCentre(), height * OPENING_PULL)
        compose.onAllNodesWithTag(SHADE_HINT_TAG, useUnmergedTree = true).assertCountEquals(0)
        release()
        assertFalse(
            "the shade came down from a swipe on a widget",
            device.wait(Until.hasObject(SHADE), GRACE_MS),
        )
    }

    @Test
    fun holdingAWidgetOffersRemove_whichDropsThePlacementAndTheHostId() {
        val itemId = placeFixtureWidget()
        awaitWidgetCell()
        val id =
            runBlocking { graph.workspace.observe(Container.HOME).first() }
                .pages
                .first()
                .items
                .first { it.id == itemId }
                .appWidgetId!!
        hold(compose.onNodeWithTag(WIDGET_TAG))
        release()
        menuRow("Remove").assertIsDisplayed()
        menuRow("Remove").performClick()
        awaitGone(WIDGET_TAG)
        waitUntil(TIMEOUT_MS) { graph.widgets.info(id) == null }
        assertNull(
            runBlocking { graph.workspace.observe(Container.HOME).first() }
                .pages
                .first()
                .items
                .firstOrNull { it.id == itemId }
        )
    }

    /** A widget's default view opens its app when tapped (the framework's doing, since 12). */
    @Test
    fun aTapOnAWidgetIsTheWidgets() {
        placeFixtureWidget()
        awaitWidgetCell()
        compose.onRoot().performTouchInput { click(widgetCentre()) }
        assertTrue(
            "the widget's app never opened",
            device.wait(Until.hasObject(By.pkg(provider.packageName)), TIMEOUT_MS),
        )
        device.pressBack()
    }

    private fun widgetCentre() =
        compose.onNodeWithTag(WIDGET_TAG).fetchSemanticsNode().boundsInRoot.center

    /** A slow pull of [dy] (root px, negative up) from [start], the finger left down at the end. */
    private fun pullFrom(start: Offset, dy: Float) {
        compose.onRoot().performTouchInput {
            down(start)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, dy / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
        }
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
        const val PULL_STEPS = 10
        const val PULL_STEP_MS = 40L // slow enough not to count as a flick
        const val OPENING_PULL = 0.35f // would open the drawer or the shade from an icon
        const val GRACE_MS = 1_000L // long enough for a shade that was going to come down
        val SHADE: BySelector = By.res("com.android.systemui", "notification_stack_scroller")
    }
}
