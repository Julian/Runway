package com.grayvines.runway

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.home.DOCK_TAG
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the real launcher on the device. Seeds the layout with every installed app, so it replaces
 * whatever layout the debug build had.
 */
@RunWith(AndroidJUnit4::class)
class LauncherFlowTest {
    @get:Rule val compose = createAndroidComposeRule<LauncherActivity>()

    private val app = ApplicationProvider.getApplicationContext<RunwayApp>()
    private val graph
        get() = app.graph

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Small enough that the fill spans several pages. */
    private val settings = Settings(columns = 4, rows = 5, dockSlots = 3)

    private lateinit var firstDockApp: String
    private lateinit var firstHomeApp: String

    @Before
    fun seed() {
        runBlocking {
            graph.settings.update { settings }
            val all = graph.appRepository.apps.first { it.isNotEmpty() }
            // Our own settings app goes in the first home cell so tests can tap it on page 1.
            val ours = all.first { it.component.packageName == app.packageName }
            val apps =
                (all - ours).take(settings.dockSlots) + ours + (all - ours).drop(settings.dockSlots)
            firstDockApp = apps[0].label
            firstHomeApp = ours.label
            graph.workspace.autoFill(
                apps.map { it.ref },
                settings.columns,
                settings.pageRows,
                settings.dockSlots,
            )
        }
        // The layout arrives through Room flows, which Compose's idling does not track.
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun tappingTheSettingsIconOpensSettings() {
        icon(firstHomeApp).performClick()
        assertTrue(
            "settings screen did not appear",
            device.wait(Until.hasObject(By.text("Grid")), TIMEOUT_MS),
        )
        device.pressBack()
    }

    @Test
    fun columnsSettingRelaysOutTheGridLive() {
        val before = icon(firstHomeApp).fetchSemanticsNode().size
        runBlocking { graph.settings.update { it.copy(columns = it.columns + 2) } }
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).fetchSemanticsNode().size != before }
    }

    @Test
    fun homeIntentReturnsToTheFirstPage() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeLeft() }
        compose.waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }

        // Addressed explicitly: the test install resets the device's default home app.
        app.startActivity(
            Intent(Intent.ACTION_MAIN, null, app, LauncherActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
    }

    @Test
    fun searchBarShowsTheHandoffTarget() {
        val target = graph.searchTargets.resolve(null)
        // Bare CI images may have no browser at all; that is not a launcher bug.
        assumeTrue("no web-search handler installed", target != null)
        compose
            .onNodeWithTag(SEARCH_TARGET_ICON_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertContentDescriptionEquals(target!!.label)
    }

    @Test
    fun iconsShareOneSizeAcrossGridAndDock() {
        val grid = icon(firstHomeApp).fetchSemanticsNode().size
        val dock = icon(firstDockApp).fetchSemanticsNode().size
        assertEquals(grid, dock)
        assertTrue(grid.width > 0)
    }

    @Test
    fun longPressDragMovesAnIconToAnEmptyCellAndBack() {
        // 5×5 page cells hold 25; the seed puts far fewer on page 1, so the last cell is free.
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.homeCell(4, 4))
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 4 to 4 }
        assertStillOnLauncher()

        // And back: the handler must see the item's new position, not its original one.
        drag(from = firstHomeApp, to = grid.homeCell(0, 0))
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 0 to 0 }
    }

    @Test
    fun droppingOnAnOccupiedCellDisplacesItsOccupant() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        drag(from = firstHomeApp, to = grid.homeCell(1, 0))
        compose.waitUntil(TIMEOUT_MS) { homeCellOf(firstHomeApp) == 1 to 0 }
        assertEquals(0 to 0, homeCellOf(neighbour)) // into the cell the mover vacated
    }

    @Test
    fun droppingIntoAnEmptyDockSlotMovesToTheDock() {
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        drag(from = firstHomeApp, to = grid.dockSlot(settings.dockSlots))
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.container == Container.DOCK }
        assertEquals(settings.dockSlots, placementOf(firstHomeApp)?.x)
    }

    @Test
    fun droppingOnAnOccupiedDockSlotSnapsBack() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.dockSlot(0))
        assertUnmoved(firstHomeApp)
        assertEquals(0, placementOf(firstDockApp)?.x)
    }

    @Test
    fun droppingOutsideAnyAreaSnapsBack() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstHomeApp, to = grid.searchBar())
        assertUnmoved(firstHomeApp)
    }

    @Test
    fun dockIconsCanBeDraggedOntoAHomePage() {
        val grid = useGrid(columns = 5, rows = 7)
        drag(from = firstDockApp, to = grid.homeCell(4, 4))
        compose.waitUntil(TIMEOUT_MS) {
            placementOf(firstDockApp)?.let { it.container == Container.HOME && it.x == 4 } ?: false
        }
    }

    // ---- drag helpers ----

    /** Screen geometry after switching to a grid, in root pixels. */
    private inner class Grid(val columns: Int, val pageRows: Int, val dockSlots: Int) {
        private val page = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        private val dock = compose.onNodeWithTag(DOCK_TAG).fetchSemanticsNode().boundsInRoot

        fun homeCell(x: Int, y: Int) =
            Offset(
                page.left + (x + 0.5f) * page.width / columns,
                page.top + (y + 0.5f) * page.height / pageRows,
            )

        fun dockSlot(slot: Int) =
            Offset(dock.left + (slot + 0.5f) * dock.width / dockSlots, dock.center.y)

        fun searchBar() = Offset(page.center.x, page.top / 2)
    }

    private fun useGrid(columns: Int, rows: Int, dockSlots: Int = settings.dockSlots): Grid {
        val before = icon(firstHomeApp).fetchSemanticsNode().size
        runBlocking {
            graph.settings.update { it.copy(columns = columns, rows = rows, dockSlots = dockSlots) }
        }
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).fetchSemanticsNode().size != before }
        return Grid(columns, rows - Settings.RESERVED_ROWS, dockSlots)
    }

    /** A long press on [from]'s icon, then a drag to [to], all in root coordinates. */
    private fun drag(from: String, to: Offset) {
        val start = icon(from).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput {
            down(start)
            advanceEventTime(LONG_PRESS_MS)
            var p = start
            repeat(DRAG_STEPS) {
                p += (to - start) / DRAG_STEPS.toFloat()
                moveTo(p)
                advanceEventTime(DRAG_STEP_MS)
            }
            up()
        }
    }

    private fun assertUnmoved(label: String) {
        compose.waitForIdle()
        assertEquals(0 to 0, homeCellOf(label))
        assertEquals(Container.HOME, placementOf(label)?.container)
    }

    /** The lifted icon is our settings app; a stray click would open its "Grid" section. */
    private fun assertStillOnLauncher() {
        compose.waitForIdle()
        assertTrue("a drag must not also launch the app", !device.hasObject(By.text("Grid")))
    }

    private fun placementOf(label: String): ItemEntity? = runBlocking {
        val component = labelToComponent(label)
        listOf(Container.HOME, Container.DOCK)
            .flatMap { graph.workspace.observe(it).first().pages }
            .flatMap { it.items }
            .firstOrNull { it.component == component }
    }

    private fun labelAtHomeCell(x: Int, y: Int): String = runBlocking {
        val item =
            graph.workspace.observe(Container.HOME).first().pages.first().items.first {
                it.x == x && it.y == y
            }
        graph.appRepository.apps
            .first { it.isNotEmpty() }
            .first { it.ref.component == item.component }
            .label
    }

    private fun homeCellOf(label: String): Pair<Int?, Int?>? =
        placementOf(label)?.takeIf { it.container == Container.HOME }?.let { it.x to it.y }

    private fun labelToComponent(label: String): String = runBlocking {
        graph.appRepository.apps.first { it.isNotEmpty() }.first { it.label == label }.ref.component
    }

    /** Icons live inside clickable cells, whose semantics merge; look at the unmerged tree. */
    private fun icon(label: String) =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    private fun SemanticsNodeInteraction.isDisplayedOrFalse() = runCatching {
        assertIsDisplayed()
        true
    }
        .getOrDefault(false)

    private companion object {
        const val TIMEOUT_MS = 5_000L
        const val LONG_PRESS_MS = 1_000L
        const val DRAG_STEPS = 10
        const val DRAG_STEP_MS = 30L
    }
}
