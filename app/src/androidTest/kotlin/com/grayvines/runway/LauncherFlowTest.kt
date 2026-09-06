package com.grayvines.runway

import android.content.Intent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlin.math.abs
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
    fun aDroppedIconIsDrawnAtItsTargetBeforeTheDatabaseCatchesUp() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtHomeCell(1, 0)
        // Released off-centre; the cell must start there and only ever approach its slot.
        holdDrag(from = firstHomeApp, to = grid.homeCell(1, 0) + Offset(grid.cellWidth() / 3, 0f))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        release()
        assertSettlesTowards(firstHomeApp, grid.homeCell(1, 0))
        assertEquals(grid.cellAt(icon(neighbour).fetchSemanticsNode().boundsInRoot.center), 0 to 0)
    }

    @Test
    fun aTouchedIconShrinksUntilReleased() {
        val resting = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput { down(resting.center) }
        // Shorter than the long-press timeout, so this is a touch and not a lift.
        compose.mainClock.advanceTimeBy(PRESS_SETTLE_MS)
        val pressed = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "pressed ${pressed.width} vs resting ${resting.width}",
            pressed.width < resting.width,
        )
        // Cancel rather than lift: lifting would complete a tap and launch the app.
        compose.onRoot().performTouchInput { cancel() }
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        assertEquals(resting.width, icon(firstHomeApp).fetchSemanticsNode().boundsInRoot.width, 1f)
    }

    @Test
    fun dwellingAtTheRightEdgeFlipsToTheNextPageAndDropsThere() {
        // The seed's 4×3 pages hold 12; page 2 holds the rest, with its bottom row free.
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val onPageTwo = labelOnPage(1)
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row = settings.pageRows - 1))
        // The dwell timer runs on real time, so wait rather than advance the test clock.
        compose.waitUntil(TIMEOUT_MS) { icon(onPageTwo).isDisplayedOrFalse() }
        compose.waitForIdle() // let the page scroll settle before dropping
        release()
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == 1 }
    }

    @Test
    fun aLongHoldPastTheLastPageAddsAPageAndDropsThere() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val pagesBefore = runBlocking { graph.workspace.observe(Container.HOME).first().pages.size }
        val onLastPage = labelOnPage(pagesBefore - 1)
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row = 0))
        // Flips to the last page first, then after the longer hold a new page appears and shows.
        compose.waitUntil(LONG_TIMEOUT_MS) {
            runBlocking { graph.workspace.observe(Container.HOME).first().pages.size } ==
                pagesBefore + 1
        }
        compose.waitUntil(LONG_TIMEOUT_MS) { !icon(onLastPage).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == pagesBefore }
    }

    @Test
    fun theHomeAreaZoomsOutWhileDragging() {
        val grid = useGrid(columns = 5, rows = 7)
        val resting = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        holdDrag(from = firstHomeApp, to = grid.homeCell(4, 4))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val zoomed = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "zoomed ${zoomed.width} vs resting ${resting.width}",
            zoomed.width < resting.width,
        )
        release()
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val back = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        assertEquals(resting.width, back.width, 1f)
    }

    @Test
    fun theLiftedIconIsDrawnLargerThanItsCellIcon() {
        val grid = useGrid(columns = 5, rows = 7)
        val resting = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot
        holdDrag(from = firstHomeApp, to = grid.homeCell(4, 4))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val lifted =
            compose
                .onNodeWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
        assertTrue(
            "lifted ${lifted.width} vs resting ${resting.width}",
            lifted.width > resting.width * 1.1f,
        )
        release()
    }

    @Test
    fun theLiftAndThePullBackMoveAsOne() {
        useGrid(columns = 5, rows = 7)
        val restingIcon = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot.width
        val restingArea =
            compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot.width
        val start = icon(firstHomeApp).fetchSemanticsNode().boundsInRoot.center
        compose.mainClock.autoAdvance = false
        try {
            compose.onRoot().performTouchInput { down(start) }
            // Run the hold, then watch frame by frame from the moment the icon lifts.
            compose.mainClock.advanceTimeBy(LIFT_HOLD_MS - FRAME_MS)
            val icons = mutableListOf<Float>()
            val areas = mutableListOf<Float>()
            repeat(LIFT_FRAMES) {
                compose.mainClock.advanceTimeByFrame()
                val overlay =
                    compose
                        .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .firstOrNull()
                if (overlay != null) {
                    icons += overlay.boundsInRoot.width
                    areas +=
                        compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot.width
                }
            }
            assertTrue("the icon never lifted", icons.size > LIFT_FRAMES / 2)
            assertTrue(
                "lift starts at the pressed size, not a jump to full: ${icons.first()} vs $restingIcon",
                icons.first() < restingIcon,
            )
            val iconPeak = icons.indexOf(icons.max())
            val areaTrough = areas.indexOf(areas.min())
            assertTrue(
                "icon peaked at frame $iconPeak but the area bottomed out at $areaTrough",
                abs(iconPeak - areaTrough) <= 1,
            )
            assertTrue(
                "no overshoot on lift: ${icons.max()} vs ${restingIcon * 1.2f}",
                icons.max() > restingIcon * 1.2f,
            )
            assertEquals(restingIcon * 1.2f, icons.last(), 2f)
            assertEquals(restingArea * 0.94f, areas.last(), 2f)
        } finally {
            compose.mainClock.autoAdvance = true
        }
        release()
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
    fun draggingADockIconOntoAnotherDockSlotReordersTheDock() {
        val grid = useGrid(columns = 5, rows = 7)
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        drag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots - 1))
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots - 1 }
        assertEquals(settings.dockSlots - 2, placementOf(lastDockApp)?.x) // shifted one left
        assertEquals(Container.DOCK, placementOf(firstDockApp)?.container)
    }

    @Test
    fun dockNeighboursSlideOverWhileTheDragIsStillInProgress() {
        val grid = useGrid(columns = 5, rows = 7)
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        holdDrag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots - 1))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        val shown = icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center
        assertEquals(settings.dockSlots - 2, grid.dockSlotAt(shown))
        release()
    }

    @Test
    fun aReorderedDockIsDrawnSettledOneFrameAfterRelease() {
        val grid = useGrid(columns = 5, rows = 7)
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        holdDrag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots - 1))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        release()
        compose.mainClock.advanceTimeByFrame()
        val shown = icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center
        assertEquals(settings.dockSlots - 2, grid.dockSlotAt(shown))
        assertSettlesTowards(firstDockApp, grid.dockSlot(settings.dockSlots - 1))
        // And it stays there while the database catches up.
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots - 1 }
        assertEquals(
            settings.dockSlots - 2,
            grid.dockSlotAt(icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center),
        )
    }

    @Test
    fun anAdjacentDockReorderSettlesWithoutRevisitingItsOldSlot() {
        val grid = useGrid(columns = 5, rows = 7)
        val neighbour = labelAtDockSlot(1)
        // Released off-centre, as a finger does: the settle has real distance to cover.
        holdDrag(from = firstDockApp, to = grid.dockSlot(1) + Offset(grid.dockSlotWidth() / 3, 0f))
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        release()
        assertSettlesTowards(firstDockApp, grid.dockSlot(1))
        assertEquals(0, grid.dockSlotAt(icon(neighbour).fetchSemanticsNode().boundsInRoot.center))
    }

    @Test
    fun droppingOutsideAnyAreaSlidesBackToItsCell() {
        val grid = useGrid(columns = 5, rows = 7)
        holdDrag(from = firstHomeApp, to = grid.searchBar())
        compose.mainClock.advanceTimeBy(LIFT_ANIMATION_MS)
        release()
        assertSettlesTowards(firstHomeApp, grid.homeCell(0, 0))
        assertUnmoved(firstHomeApp)
    }

    @Test
    fun movingADockIconIntoAnEmptySlotLeavesTheOthersAlone() {
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        val neighbour = labelAtDockSlot(1)
        val last = labelAtDockSlot(settings.dockSlots - 1)
        drag(from = firstDockApp, to = grid.dockSlot(settings.dockSlots))
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstDockApp)?.x == settings.dockSlots }
        assertEquals(1, placementOf(neighbour)?.x)
        assertEquals(settings.dockSlots - 1, placementOf(last)?.x)
        assertEquals(1, grid.dockSlotAt(icon(neighbour).fetchSemanticsNode().boundsInRoot.center))
    }

    @Test
    fun aDropAfterAPageFlipSettlesIntoItsCellOnTheNewPage() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val onPageTwo = labelOnPage(1)
        val row = settings.pageRows - 1
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row))
        compose.waitUntil(TIMEOUT_MS) { icon(onPageTwo).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        assertSettlesTowards(firstHomeApp, grid.homeCell(settings.columns - 1, row))
        compose.waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == 1 }
    }

    @Test
    fun aDockIconHeldAtAHomeEdgeFlipsThePage() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val onPageTwo = labelOnPage(1)
        holdDrag(from = firstDockApp, to = grid.rightEdge(row = settings.pageRows - 1))
        compose.waitUntil(TIMEOUT_MS) { icon(onPageTwo).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        compose.waitUntil(TIMEOUT_MS) {
            placementOf(firstDockApp)?.let { it.container == Container.HOME && it.pageIndex == 1 }
                ?: false
        }
    }

    @Test
    fun aLongHoldAtTheDocksEdgeAddsADockPageAndDropsThere() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        holdDrag(from = firstHomeApp, to = grid.dockRightEdge())
        compose.waitUntil(LONG_TIMEOUT_MS) { dockPageCount() == 2 }
        compose.waitUntil(LONG_TIMEOUT_MS) { !icon(firstDockApp).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        compose.waitUntil(TIMEOUT_MS) {
            placementOf(firstHomeApp)?.let { it.container == Container.DOCK && it.pageIndex == 1 }
                ?: false
        }
        assertEquals(settings.dockSlots - 1, placementOf(firstHomeApp)?.x)
    }

    @Test
    fun holdingAtTheDocksLeftEdgeFlipsBackAndTheIconCanBeDroppedThere() {
        // One spare slot on the first dock page, to come back to.
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        runBlocking { graph.workspace.addPage(Container.DOCK, 1) }
        compose.waitUntil(TIMEOUT_MS) { dockPageCount() == 2 }
        // Put an icon on the second dock page, then lift it and hold at the dock's left edge.
        holdDrag(from = firstHomeApp, to = grid.dockRightEdge())
        compose.waitUntil(LONG_TIMEOUT_MS) { !icon(firstDockApp).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        compose.waitUntil(LONG_TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == 1 }
        holdDrag(from = firstHomeApp, to = grid.dockLeftEdge())
        compose.waitUntil(LONG_TIMEOUT_MS) { icon(firstDockApp).isDisplayedOrFalse() }
        // Back on the first page: carry it to the spare slot, entering from inside the edge zone.
        dragOn(to = grid.dockSlot(settings.dockSlots) - Offset(grid.dockSlotWidth() / 3, 0f))
        release()
        compose.waitUntil(TIMEOUT_MS) {
            placementOf(firstHomeApp)?.let { it.pageIndex == 0 && it.x == settings.dockSlots }
                ?: false
        }
        assertEquals(2, dockPageCount()) // no third page from holding at the left
    }

    @Test
    fun liftingTheLastDockIconAndHoldingStillAddsNoPage() {
        // The last slot's icon sits inside the edge zone; merely lifting it must not flip.
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        val start = icon(lastDockApp).fetchSemanticsNode().boundsInRoot.center
        holdDrag(from = lastDockApp, to = start)
        Thread.sleep(EDGE_ADD_MS)
        compose.waitForIdle()
        assertEquals(1, dockPageCount())
        assertTrue(icon(firstDockApp).isDisplayedOrFalse()) // still on the first dock page
        release()
        compose.waitForIdle()
        assertEquals(settings.dockSlots - 1, placementOf(lastDockApp)?.x)
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

        /** Just inside the right edge zone, on [row]. */
        fun rightEdge(row: Int) =
            Offset(
                page.right - page.width * 0.02f,
                page.top + (row + 0.5f) * page.height / pageRows,
            )

        /** Just inside the dock's right or left edge zone. */
        fun dockRightEdge() = Offset(dock.right - dock.width * 0.02f, dock.center.y)

        fun dockLeftEdge() = Offset(dock.left + dock.width * 0.02f, dock.center.y)

        fun dockSlotWidth() = dock.width / dockSlots

        fun cellWidth() = page.width / columns

        /** The dock slot a root-pixel point falls in. */
        fun dockSlotAt(p: Offset) = ((p.x - dock.left) / (dock.width / dockSlots)).toInt()

        /** The home cell a root-pixel point falls in. */
        fun cellAt(p: Offset) =
            ((p.x - page.left) / (page.width / columns)).toInt() to
                ((p.y - page.top) / (page.height / pageRows)).toInt()
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
        holdDrag(from, to)
        release()
    }

    /** Like [drag] but leaves the finger down at [to]. */
    private fun holdDrag(from: String, to: Offset) {
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
        }
    }

    private fun release() {
        compose.onRoot().performTouchInput { up() }
    }

    /** Moves the held finger on to [to] in steps. */
    private fun dragOn(to: Offset) {
        compose.onRoot().performTouchInput {
            val start = currentPosition()!!
            var p = start
            repeat(DRAG_STEPS) {
                p += (to - start) / DRAG_STEPS.toFloat()
                moveTo(p)
                advanceEventTime(DRAG_STEP_MS)
            }
        }
    }

    /**
     * Frame by frame after a release, the dropped icon only ever gets closer to [slotCentre]: no
     * jumping back to where it came from, no shaking. Positions are logged for diagnosis.
     */
    private fun assertSettlesTowards(label: String, slotCentre: Offset) {
        // With the clock auto-advancing, fetching a node runs every animation to completion first,
        // so nothing in between would ever be observed. Hold the clock and step it by hand.
        compose.mainClock.autoAdvance = false
        try {
            // The overlay carries the icon while it settles; afterwards the cell shows it.
            fun icon() =
                compose
                    .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .firstOrNull()
                    ?: compose
                        .onNode(hasContentDescription(label), useUnmergedTree = true)
                        .fetchSemanticsNode()
            compose.mainClock
                .advanceTimeByFrame() // the first held frame is still the pre-release one
            var distance = (icon().boundsInRoot.center - slotCentre).getDistance()
            var width = icon().boundsInRoot.width
            android.util.Log.d(
                "RunwaySettle",
                "frame 1: ${icon().boundsInRoot.center} distance $distance width $width",
            )
            repeat(SETTLE_FRAMES) { frame ->
                compose.mainClock.advanceTimeByFrame()
                val bounds = icon().boundsInRoot
                val now = (bounds.center - slotCentre).getDistance()
                android.util.Log.d(
                    "RunwaySettle",
                    "frame ${frame + 2}: ${bounds.center} distance $now width ${bounds.width}",
                )
                assertTrue(
                    "moved away from its slot: $distance -> $now",
                    now <= distance + SETTLE_TOLERANCE_PX,
                )
                assertTrue(
                    "grew while settling: $width -> ${bounds.width}",
                    bounds.width <= width + SETTLE_TOLERANCE_PX,
                )
                distance = now
                width = bounds.width
            }
        } finally {
            compose.mainClock.autoAdvance = true
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

    private fun dockPageCount() = runBlocking {
        graph.workspace.observe(Container.DOCK).first().pages.size
    }

    private fun labelOnPage(page: Int): String = runBlocking {
        val item =
            graph.workspace
                .observe(Container.HOME)
                .first()
                .pages
                .first { it.index == page }
                .items
                .first()
        graph.appRepository.apps
            .first { it.isNotEmpty() }
            .first { it.ref.component == item.component }
            .label
    }

    private fun labelAtDockSlot(slot: Int): String = runBlocking {
        val item =
            graph.workspace.observe(Container.DOCK).first().pages.first().items.first {
                it.x == slot
            }
        graph.appRepository.apps
            .first { it.isNotEmpty() }
            .first { it.ref.component == item.component }
            .label
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
        /** Longer than the dwell that adds a page, in real time. */
        const val EDGE_ADD_MS = 2_500L
        const val LIFT_HOLD_MS = 550L
        const val FRAME_MS = 16L
        const val LIFT_FRAMES = 60
        const val DRAG_STEPS = 10
        const val DRAG_STEP_MS = 30L
        const val LIFT_ANIMATION_MS = 1_000L
        const val LONG_TIMEOUT_MS = 15_000L
        const val PRESS_SETTLE_MS = 250L
        const val SETTLE_FRAMES = 24
        const val SETTLE_TOLERANCE_PX = 2f
    }
}
