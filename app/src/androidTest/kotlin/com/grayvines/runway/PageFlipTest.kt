package com.grayvines.runway

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.SEARCH_BAR_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Edge dwells: flipping pages, creating them, and pruning them, on home and on the dock. */
@RunWith(AndroidJUnit4::class)
class PageFlipTest : LauncherFixture() {
    @Test
    fun dwellingAtTheRightEdgeFlipsToTheNextPageAndDropsThere() {
        // The seed's 4×3 pages hold 12; page 2 holds the rest, with its bottom row free.
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val onPageTwo = labelOnPage(1)
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row = settings.pageRows - 1))
        // The dwell timer runs on real time, so wait rather than advance the test clock.
        waitUntil(TIMEOUT_MS) { icon(onPageTwo).isDisplayedOrFalse() }
        compose.waitForIdle() // let the page scroll settle before dropping
        release()
        waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == 1 }
    }

    @Test
    fun aLongHoldPastTheLastPageAddsAPageAndDropsThere() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val pagesBefore = runBlocking { graph.workspace.observe(Container.HOME).first().pages.size }
        val onLastPage = labelOnPage(pagesBefore - 1)
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row = 0))
        // Flips to the last page first, then after the longer hold a new page appears and shows.
        waitUntil(LONG_TIMEOUT_MS) {
            runBlocking { graph.workspace.observe(Container.HOME).first().pages.size } ==
                pagesBefore + 1
        }
        waitUntil(LONG_TIMEOUT_MS) { !icon(onLastPage).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == pagesBefore }
    }

    @Test
    fun aDropRefusedAfterAPageFlipComesBackToItsCellOnTheOldPage() {
        val grid = useGrid(columns = 5, rows = 7)
        val onPageTwo = labelOnPage(1)
        holdDrag(firstHomeApp, to = grid.rightEdge(0))
        waitUntil(FLIP_WATCH_MS) { icon(onPageTwo).isDisplayedOrFalse() } // page 2 shown
        val bar = compose.onNodeWithTag(SEARCH_BAR_TAG).fetchSemanticsNode().boundsInRoot.center
        dragOn(to = bar) // over the search bar: nowhere to drop
        release()
        // Back on page 1, with the icon settled in its cell, well inside the settle timeout.
        waitUntil(SETTLE_MS) { !icon(onPageTwo).isDisplayedOrFalse() }
        awaitGone(DRAG_OVERLAY_TAG)
        cellIcon(firstHomeApp).assertIsDisplayed()
        assertEquals(0 to 0, homeCellOf(firstHomeApp))
    }

    @Test
    fun aReleaseWhileTheFlipIsStillScrollingLandsUnderTheFingerOnTheNewPage() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val marker = labelAtHomeCell(1, 0) // on page 1; its cell slides left as page 2 comes in
        val row = settings.pageRows - 1
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row))
        // The dwell runs on real time, the flip's scroll on the composition clock: hold the clock
        // and step it until the page is seen part-way across, then let go right there.
        compose.mainClock.autoAdvance = false
        val restingLeft = cellIcon(marker).fetchSemanticsNode().boundsInRoot.left
        val pageWidth = grid.cellWidth() * settings.columns
        try {
            val deadline = SystemClock.uptimeMillis() + FLIP_WATCH_MS
            var shift = 0f
            while (SystemClock.uptimeMillis() < deadline && shift < grid.cellWidth()) {
                compose.mainClock.advanceTimeByFrame()
                shift = restingLeft - cellIcon(marker).fetchSemanticsNode().boundsInRoot.left
            }
            assertTrue("the page never started scrolling", shift >= grid.cellWidth())
            assertTrue("the scroll was already over: $shift of $pageWidth", shift < pageWidth / 2)
            release()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == 1 }
        val placed = placementOf(firstHomeApp)!!
        assertEquals(settings.columns - 1 to row, placed.x to placed.y)
    }

    @Test
    fun aDropAfterAPageFlipSettlesIntoItsCellOnTheNewPage() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val onPageTwo = labelOnPage(1)
        val row = settings.pageRows - 1
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row))
        waitUntil(TIMEOUT_MS) { icon(onPageTwo).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        assertSettlesTowards(firstHomeApp, grid.homeCell(settings.columns - 1, row))
        waitUntil(TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == 1 }
    }

    @Test
    fun aDockIconHeldAtAHomeEdgeFlipsThePage() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val onPageTwo = labelOnPage(1)
        holdDrag(from = firstDockApp, to = grid.rightEdge(row = settings.pageRows - 1))
        waitUntil(TIMEOUT_MS) { icon(onPageTwo).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        waitUntil(TIMEOUT_MS) {
            placementOf(firstDockApp)?.let { it.container == Container.HOME && it.pageIndex == 1 }
                ?: false
        }
    }

    @Test
    fun aLongHoldAtTheDocksEdgeAddsADockPageAndDropsThere() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        holdDrag(from = firstHomeApp, to = grid.dockRightEdge())
        waitUntil(LONG_TIMEOUT_MS) { dockPageCount() == 2 }
        waitUntil(LONG_TIMEOUT_MS) { !icon(firstDockApp).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        waitUntil(TIMEOUT_MS) {
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
        waitUntil(TIMEOUT_MS) { dockPageCount() == 2 }
        // Put an icon on the second dock page, then lift it and hold at the dock's left edge.
        holdDrag(from = firstHomeApp, to = grid.dockRightEdge())
        waitUntil(LONG_TIMEOUT_MS) { !icon(firstDockApp).isDisplayedOrFalse() }
        compose.waitForIdle()
        release()
        waitUntil(LONG_TIMEOUT_MS) { placementOf(firstHomeApp)?.pageIndex == 1 }
        holdDrag(from = firstHomeApp, to = grid.dockLeftEdge())
        waitUntil(LONG_TIMEOUT_MS) { icon(firstDockApp).isDisplayedOrFalse() }
        // Back on the first page: carry it to the spare slot, entering from inside the edge zone.
        dragOn(to = grid.dockSlot(settings.dockSlots) - Offset(grid.dockSlotWidth() / 3, 0f))
        release()
        waitUntil(TIMEOUT_MS) {
            placementOf(firstHomeApp)?.let { it.pageIndex == 0 && it.x == settings.dockSlots }
                ?: false
        }
        waitUntil(TIMEOUT_MS) { dockPageCount() == 1 } // the emptied page is pruned
    }

    @Test
    fun liftingTheLastDockIconAndHoldingStillAddsNoPage() {
        // Picked up by its edge, so the finger is inside the edge zone from the start; merely
        // lifting it, and a nudge that stays in the zone, must not flip.
        val lastDockApp = labelAtDockSlot(settings.dockSlots - 1)
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        val start = grid.dockRightEdge()
        holdDragAt(start, start + Offset(0f, -LIFT_NUDGE_PX))
        Thread.sleep(EDGE_ADD_MS)
        compose.waitForIdle()
        assertEquals(1, dockPageCount())
        assertTrue(icon(firstDockApp).isDisplayedOrFalse()) // still on the first dock page
        release()
        compose.waitForIdle()
        assertEquals(settings.dockSlots - 1, placementOf(lastDockApp)?.x)
    }

    @Test
    fun aPageAddedDuringADragGoesAwayIfNothingLandsOnIt() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        holdDrag(from = firstHomeApp, to = grid.dockRightEdge())
        waitUntil(LONG_TIMEOUT_MS) { dockPageCount() == 2 }
        // Change of mind: back to where it came from.
        dragOn(to = grid.homeCell(0, 0))
        release()
        waitUntil(TIMEOUT_MS) { dockPageCount() == 1 }
        assertUnmoved(firstHomeApp)
    }

    @Test
    fun hoveringTheCentreOfAnOuterCellOrSlotNeverFlips() {
        val grid = useGrid(columns = 5, rows = 7, dockSlots = settings.dockSlots + 1)
        val onPageTwo = labelOnPage(1)
        holdDrag(from = firstHomeApp, to = grid.homeCell(4, 3))
        Thread.sleep(EDGE_FLIP_MS)
        compose.waitForIdle()
        assertFalse(icon(onPageTwo).isDisplayedOrFalse()) // still on the first page
        // On to the dock's empty outer slot, and hold there too.
        dragOn(to = grid.dockSlot(settings.dockSlots))
        Thread.sleep(EDGE_FLIP_MS)
        compose.waitForIdle()
        assertEquals(1, dockPageCount())
        release()
        waitUntil(TIMEOUT_MS) {
            placementOf(firstHomeApp)?.let {
                it.container == Container.DOCK && it.pageIndex == 0 && it.x == settings.dockSlots
            } ?: false
        }
    }

    @Test
    fun edgeFlippingVisitsEveryPageAndLetsEachSettle() {
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        // Three pages, each with an icon to recognise it by.
        val onPageTwo = labelOnPage(1)
        runBlocking {
            graph.workspace.addPage(Container.HOME, 2)
            graph.workspace.moveItem(
                placementOf(onPageTwo)!!.id,
                Container.HOME,
                2,
                0,
                0,
                emptyMap(),
            )
        }
        waitUntil(TIMEOUT_MS) { placementOf(onPageTwo)?.pageIndex == 2 }
        val onPageOne = labelOnPage(1)
        // Page 0's marker is a neighbour, not the dragged icon: its cell goes when the page does.
        val pages = listOf(labelAtHomeCell(1, 0), onPageOne, onPageTwo)
        holdDrag(from = firstHomeApp, to = grid.rightEdge(row = settings.pageRows - 1))
        // Which page is settled, sampled in real time until the last page shows. Fetching a node
        // blocks while the pager animates, so a sample is (started, finished, page): -1 while
        // scrolling. The home area is zoomed out during a drag, so cells are measured from the
        // workspace as it is drawn.
        val cells = pages.map { label -> placementOf(label)!!.let { it.x!! to it.y!! } }
        val samples = mutableListOf<Triple<Long, Long, Int>>()
        val deadline = SystemClock.uptimeMillis() + FLIP_WATCH_MS
        while (SystemClock.uptimeMillis() < deadline && samples.lastOrNull()?.third != 2) {
            Thread.sleep(FLIP_SAMPLE_MS)
            val started = SystemClock.uptimeMillis()
            val area = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
            val page = pages.indexOfFirst { label ->
                val (x, y) = cells[pages.indexOf(label)]
                val expected =
                    Offset(
                        area.left + (x + 0.5f) * area.width / settings.columns,
                        area.top + (y + 0.5f) * area.height / settings.pageRows,
                    )
                // A page flipped away from may be disposed, and with it the marker's cell.
                val cell =
                    compose
                        .onAllNodes(
                            hasContentDescription(label) and !hasTestTag(DRAG_OVERLAY_TAG),
                            useUnmergedTree = true,
                        )
                        .fetchSemanticsNodes()
                        .firstOrNull()
                cell != null &&
                    (cell.boundsInRoot.center - expected).getDistance() < SETTLE_TOLERANCE_PX
            }
            samples += Triple(started, SystemClock.uptimeMillis(), page)
        }
        release()
        val t0 = samples.first().first
        val trace = samples.joinToString { "${it.first - t0}..${it.second - t0}:${it.third}" }
        android.util.Log.d("RunwayFlip", trace)
        val visited = samples.map { it.third }.filter { it >= 0 }.distinct()
        assertEquals("pages in order, none skipped: $trace", listOf(0, 1, 2), visited)
        // Page 1 rests before the next flip: from when it was first seen settled until the sample
        // that blocked on the scroll to page 2 began. A queued flip would scroll on almost at once.
        val settledOnOne = samples.first { it.third == 1 }.second
        val leftOne = samples.first { it.third == 2 }.first
        assertTrue(
            "page 1 rested only ${leftOne - settledOnOne} ms: $trace",
            leftOne - settledOnOne >= MIN_REST_MS,
        )
    }

    private companion object {
        /** Less than the overlay's two-second safety net: the icon must come back sooner. */
        const val SETTLE_MS = 1_500L
    }
}
