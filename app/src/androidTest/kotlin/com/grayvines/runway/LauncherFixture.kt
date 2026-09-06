package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.home.DOCK_TAG
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule

/**
 * Drives the real launcher on the device: one seeded layout per test, geometry helpers, and drag
 * gestures. Suites extend this and hold only tests. Seeding fills the layout with every installed
 * app, so it replaces whatever layout the debug build had.
 */
open class LauncherFixture {
    @get:Rule val compose = createAndroidComposeRule<LauncherActivity>()

    protected val app = ApplicationProvider.getApplicationContext<RunwayApp>()
    protected val graph
        get() = app.graph

    protected val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    /** Small enough that the fill spans several pages. */
    protected val settings = Settings(columns = 4, rows = 5, dockSlots = 3)

    protected lateinit var firstDockApp: String
    protected lateinit var firstHomeApp: String

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

    /** Screen geometry after switching to a grid, in root pixels. */
    protected inner class Grid(val columns: Int, val pageRows: Int, val dockSlots: Int) {
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

    protected fun useGrid(columns: Int, rows: Int, dockSlots: Int = settings.dockSlots): Grid {
        val before = icon(firstHomeApp).fetchSemanticsNode().size
        runBlocking {
            graph.settings.update { it.copy(columns = columns, rows = rows, dockSlots = dockSlots) }
        }
        compose.waitUntil(TIMEOUT_MS) { icon(firstHomeApp).fetchSemanticsNode().size != before }
        return Grid(columns, rows - Settings.RESERVED_ROWS, dockSlots)
    }

    /** A long press on [from]'s icon, then a drag to [to], all in root coordinates. */
    protected fun drag(from: String, to: Offset) {
        holdDrag(from, to)
        release()
    }

    /** Like [drag] but leaves the finger down at [to]. */
    protected fun holdDrag(from: String, to: Offset) {
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

    protected fun release() {
        compose.onRoot().performTouchInput { up() }
    }

    /** Moves the held finger on to [to] in steps. */
    protected fun dragOn(to: Offset) {
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
    protected fun assertSettlesTowards(label: String, slotCentre: Offset) {
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

    protected fun assertUnmoved(label: String) {
        compose.waitForIdle()
        assertEquals(0 to 0, homeCellOf(label))
        assertEquals(Container.HOME, placementOf(label)?.container)
    }

    /** The lifted icon is our settings app; a stray click would open its "Grid" section. */
    protected fun assertStillOnLauncher() {
        compose.waitForIdle()
        assertTrue("a drag must not also launch the app", !device.hasObject(By.text("Grid")))
    }

    protected fun placementOf(label: String): ItemEntity? = runBlocking {
        val component = labelToComponent(label)
        listOf(Container.HOME, Container.DOCK)
            .flatMap { graph.workspace.observe(it).first().pages }
            .flatMap { it.items }
            .firstOrNull { it.component == component }
    }

    protected fun dockPageCount() = runBlocking {
        graph.workspace.observe(Container.DOCK).first().pages.size
    }

    protected fun labelOnPage(page: Int): String = runBlocking {
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

    protected fun labelAtDockSlot(slot: Int): String = runBlocking {
        val item =
            graph.workspace.observe(Container.DOCK).first().pages.first().items.first {
                it.x == slot
            }
        graph.appRepository.apps
            .first { it.isNotEmpty() }
            .first { it.ref.component == item.component }
            .label
    }

    protected fun labelAtHomeCell(x: Int, y: Int): String = runBlocking {
        val item =
            graph.workspace.observe(Container.HOME).first().pages.first().items.first {
                it.x == x && it.y == y
            }
        graph.appRepository.apps
            .first { it.isNotEmpty() }
            .first { it.ref.component == item.component }
            .label
    }

    protected fun homeCellOf(label: String): Pair<Int?, Int?>? =
        placementOf(label)?.takeIf { it.container == Container.HOME }?.let { it.x to it.y }

    protected fun labelToComponent(label: String): String = runBlocking {
        graph.appRepository.apps.first { it.isNotEmpty() }.first { it.label == label }.ref.component
    }

    /** Icons live inside clickable cells, whose semantics merge; look at the unmerged tree. */
    protected fun icon(label: String) =
        compose.onNodeWithContentDescription(label, useUnmergedTree = true)

    /** The icon in its cell, even while a copy of it is being dragged in the overlay. */
    protected fun cellIcon(label: String) =
        compose.onNode(
            hasContentDescription(label) and !hasTestTag(DRAG_OVERLAY_TAG),
            useUnmergedTree = true,
        )

    protected fun SemanticsNodeInteraction.isDisplayedOrFalse() = runCatching {
        assertIsDisplayed()
        true
    }
        .getOrDefault(false)
}

const val TIMEOUT_MS = 5_000L
const val LONG_PRESS_MS = 1_000L
/** Longer than the dwell that adds a page, in real time. */
const val EDGE_ADD_MS = 2_500L
/** Longer than the dwell that flips a page, in real time. */
const val EDGE_FLIP_MS = 1_200L
const val FLIP_SAMPLE_MS = 30L
const val FLIP_WATCH_MS = 2_500L
const val MIN_REST_MS = 100L // the dwell is 450 ms and the scroll 250 ms
const val LIFT_HOLD_MS = 550L

/** Past touch slop: enough movement after a hold to turn it into a drag. */
const val LIFT_NUDGE_PX = 60f
const val FRAME_MS = 16L
const val LIFT_FRAMES = 60
const val DRAG_STEPS = 10
const val DRAG_STEP_MS = 30L
const val LIFT_ANIMATION_MS = 1_000L
const val LONG_TIMEOUT_MS = 15_000L
const val PRESS_SETTLE_MS = 250L
const val SETTLE_FRAMES = 24
const val SETTLE_TOLERANCE_PX = 2f
