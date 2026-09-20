package com.grayvines.runway

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat.Type
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.addWidget
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.data.observeHiddenApps
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.unhideApp
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.LabelOrder
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DropAreas
import com.grayvines.runway.ui.drag.DropTarget
import com.grayvines.runway.ui.drag.EDGE_CELL_FRACTION
import com.grayvines.runway.ui.drag.Edge
import com.grayvines.runway.ui.drag.EdgeDwell
import com.grayvines.runway.ui.drag.EdgeHover
import com.grayvines.runway.ui.drag.FOLD_KEEP_ZONE
import com.grayvines.runway.ui.drag.FOLD_ZONE
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.cellUnder
import com.grayvines.runway.ui.drag.centreOf
import com.grayvines.runway.ui.drag.edgeAt
import com.grayvines.runway.ui.drag.homeCellAt
import com.grayvines.runway.ui.drawer.DRAWER_ITEM_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import com.grayvines.runway.ui.folder.FOLDER_ITEM_TAG
import com.grayvines.runway.ui.folder.FOLDER_TAG
import com.grayvines.runway.ui.home.DOCK_TAG
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.FLIP_SCROLL_MS
import com.grayvines.runway.ui.home.ICON_INSET
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import com.grayvines.runway.ui.menu.HOME_MENU_TAG
import com.grayvines.runway.ui.menu.ITEM_MENU_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit.MILLISECONDS
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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

    /**
     * The apps tests may refer to by label: those with a label of their own, sorted as the drawer
     * sorts.
     */
    protected lateinit var apps: List<AppEntry>

    protected val labels: List<String>
        get() = apps.map { it.label }

    /** Widget host ids allocated by this test, to give back after it. */
    private val boundIds = mutableListOf<Int>()

    @Before
    fun seed() {
        runBlocking {
            graph.settings.update { settings }
            // Hidden apps are not part of the layout, so the fill below leaves them hidden.
            graph.workspace.observeHiddenApps().first().forEach { graph.workspace.unhideApp(it) }
            // Tests find icons by label, so two apps with the same one (a stock image ships two
            // "Chrome"s) would be indistinguishable: such apps stay out of the layout.
            val all =
                graph.appRepository.apps
                    .first { it.isNotEmpty() }
                    .groupBy { it.label }
                    .values
                    .mapNotNull { it.singleOrNull() }
                    .sortedWith(compareBy(LabelOrder.comparator()) { it.label })
            apps = all
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
        // The layout arrives through Room flows and the settings through DataStore, which
        // Compose's idling does not track; the activity was up before either, so the screen may
        // still show the previous test's grid until they land.
        awaitGrid(settings.columns, settings.pageRows)
        awaitSeededLayout()
        // Touches injected before the window has focus are refused ("Failed to inject touch
        // input"): the previous test's activity may still be on its way out on a slow device.
        awaitWindowFocus()
        awaitQuietDevice()
        dismissKeyboard()
    }

    /**
     * A test that ends with the keyboard up (a folder's name half typed) would leave the system
     * bringing it back over the next test's launcher on a slow image, and handing that test's first
     * Back press to the keyboard instead of the launcher. So the keyboard is put away, and seen to
     * be gone, before the activity goes; and once more, cheaply, before the next test touches
     * anything.
     */
    @After
    fun putKeyboardAway() {
        val activity = runCatching { compose.activity }.getOrNull() ?: return
        if (activity.isDestroyed) return
        // Never masks the test's own failure: a keyboard that will not go is not this test's fault.
        runCatching { dismissKeyboard() }
    }

    /**
     * The host ids allocated for this test are given back: the layout they sat in is reseeded
     * anyway, and on a device that is not thrown away after the run they would otherwise pile up.
     */
    @After
    fun releaseBoundWidgets() {
        boundIds.forEach { runCatching { graph.widgets.deleteId(it) } }
        boundIds.clear()
    }

    /** The shade outlives a test that pulled it down; the next test wants the home screen. */
    @After
    fun collapseShade() {
        if (!device.hasObject(SHADE)) return
        device.executeShellCommand("cmd statusbar collapse")
        device.wait(Until.gone(SHADE), TIMEOUT_MS)
    }

    /**
     * A slow pull of [dy] (root px, negative up) from [start], the finger left down at the end: a
     * drawer or shade gesture rather than a flick.
     */
    protected fun pullFrom(start: Offset, dy: Float) {
        compose.onRoot().performTouchInput {
            down(start)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, dy / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
        }
    }

    private fun dismissKeyboard() {
        val window = compose.activity.window
        compose.runOnUiThread {
            window.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(window, window.decorView).hide(Type.ime())
        }
        awaitNoKeyboard(TIMEOUT_MS)
    }

    /**
     * Waits for the keyboard to be gone. The insets say "hidden" as soon as the hide is asked for;
     * the keyboard's own window is still on its way out, and a window torn down under it leaves the
     * system bringing the keyboard back for the next one, so wait for the window itself as well.
     */
    protected fun awaitNoKeyboard(timeoutMillis: Long = LONG_TIMEOUT_MS) {
        val window = compose.activity.window
        val keyboard = keyboardPackage()
        waitUntil(timeoutMillis) {
            ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(Type.ime()) != true &&
                (keyboard == null || !device.hasObject(By.pkg(keyboard)))
        }
    }

    /**
     * Back, meant for the launcher. While the keyboard is up the system hands back to it first, to
     * put it away, and the press never reaches the app at all, so this waits for the keyboard to
     * have gone. A press meant for the keyboard itself, or for another app in front, goes through
     * [device] instead.
     */
    protected fun pressBack() {
        awaitNoKeyboard()
        device.pressBack()
    }

    /**
     * Waits for the keyboard to be up: asked for, and its window on screen. Keystrokes injected
     * from the shell while it is still attaching are swallowed, so a test that types that way must
     * wait for it, focus on the field alone is not enough.
     */
    protected fun awaitKeyboard() {
        val window = compose.activity.window
        val keyboard = keyboardPackage()
        waitUntil(LONG_TIMEOUT_MS) {
            ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(Type.ime()) == true &&
                (keyboard == null || device.hasObject(By.pkg(keyboard)))
        }
    }

    /** The current keyboard app, whose window is what shows on screen; null if none is set. */
    private fun keyboardPackage(): String? =
        android.provider.Settings.Secure.getString(
                app.contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
            )
            ?.substringBefore('/')

    /**
     * Waits for the launcher's window to have focus. A hung app's dialog (see
     * [dismissNotRespondingDialog]) is closed first, and once more if the wait runs out, since it
     * can come up during the wait; then the failure says what held focus.
     */
    private fun awaitWindowFocus() {
        repeat(FOCUS_ATTEMPTS) { attempt ->
            dismissNotRespondingDialog()
            try {
                compose.waitUntil(LONG_TIMEOUT_MS) { compose.activity.hasWindowFocus() }
                return
            } catch (e: ComposeTimeoutException) {
                if (attempt == FOCUS_ATTEMPTS - 1) {
                    throw AssertionError("the launcher never got window focus; ${windowFocus()}", e)
                }
            }
        }
    }

    /**
     * Waits, once per process, for a freshly booted device to go quiet. The first test of a run
     * otherwise shares the emulator with the tail of its boot: the system kills and restarts the
     * stock launcher, which rebuilds the navigation bar under the test, so the launcher's areas
     * move after a test has measured them, and frames come seconds late. Quiet is the processor
     * mostly idle and the system bars holding still, several samples running. A device that never
     * goes quiet is left for the tests to judge.
     */
    private fun awaitQuietDevice() {
        if (deviceWentQuiet) return
        val deadline = SystemClock.uptimeMillis() + QUIET_TIMEOUT_MS
        var cpu = cpuTimes()
        var bars = systemBars()
        var quiet = 0
        while (quiet < QUIET_SAMPLES && SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(QUIET_SAMPLE_MS)
            val nextCpu = cpuTimes()
            val nextBars = systemBars()
            val idle = (nextCpu.idle - cpu.idle).toFloat() / maxOf(1L, nextCpu.total - cpu.total)
            quiet = if (idle >= QUIET_IDLE_FRACTION && nextBars == bars) quiet + 1 else 0
            cpu = nextCpu
            bars = nextBars
        }
        deviceWentQuiet = true
    }

    private class CpuTimes(val idle: Long, val total: Long)

    /** The device's processor time so far, from the summary line of `/proc/stat`. */
    private fun cpuTimes(): CpuTimes {
        val times =
            device
                .executeShellCommand("cat /proc/stat")
                .lineSequence()
                .first()
                .split(' ')
                .mapNotNull { it.toLongOrNull() }
        return CpuTimes(idle = times[IDLE_COLUMN], total = times.sum())
    }

    private fun systemBars() =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(Type.systemBars())

    /**
     * Grants or revokes the launcher's right to bind widgets without asking, through the shell, as
     * the system's bind dialog would grant it. By user number: the command refuses "current".
     */
    protected fun allowWidgetBinding(allowed: Boolean) {
        val user = device.executeShellCommand("am get-current-user").trim()
        val verb = if (allowed) "grantbind" else "revokebind"
        device.executeShellCommand("appwidget $verb --package ${app.packageName} --user $user")
    }

    /**
     * Runs [action] and waits for a toast saying [text]. A toast is no window UiAutomator can look
     * at; it reaches accessibility as an event, which is what is watched here.
     */
    protected fun expectToast(text: String, action: () -> Unit) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val seen = CountDownLatch(1)
        // A listener rather than executeAndWaitForEvent: the action may itself go through
        // UiAutomation (a click on a system dialog), which that cannot nest.
        automation.setOnAccessibilityEventListener { event ->
            if (
                event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                    event.text.any { it.toString() == text }
            ) {
                seen.countDown()
            }
        }
        try {
            action()
            assertTrue("no toast saying \"$text\"", seen.await(LONG_TIMEOUT_MS, MILLISECONDS))
        } finally {
            automation.setOnAccessibilityEventListener(null)
        }
    }

    /** Empties [cells] of the first home page and waits for the screen to show them empty. */
    protected fun clearHomeCells(vararg cells: Pair<Int, Int>) {
        val removed = runBlocking {
            val page = graph.workspace.observe(Container.HOME).first().pages.first()
            page.items
                .filter { it.x to it.y in cells }
                .onEach { graph.workspace.removeItem(it.id) }
                .map { item -> apps.first { it.ref.component == item.component }.label }
        }
        waitUntil { removed.none { icon(it).isDisplayedOrFalse() } }
    }

    /**
     * Binds one of the fixture's widgets ([provider]) the way the picker does and puts it over
     * [spanX] × [spanY] cells of the first page from ([x], [y]), whose icons make way; the
     * placement's id. Not [bound], the id has no provider behind it, as when the widget's app has
     * been uninstalled.
     */
    protected fun placeFixtureWidget(
        x: Int,
        y: Int,
        spanX: Int = 2,
        spanY: Int = 1,
        bound: Boolean = true,
        provider: ComponentName = FIXTURE_WIDGET,
    ): Long {
        allowWidgetBinding(true)
        val id = graph.widgets.allocateId().also { boundIds += it }
        if (bound) {
            assertTrue(
                "could not bind $provider",
                graph.widgets.bind(id, provider, Process.myUserHandle()),
            )
        }
        return runBlocking {
            val page = graph.workspace.observe(Container.HOME).first().pages.first()
            page.items
                .filter { it.x in x until x + spanX && it.y in y until y + spanY }
                .forEach { graph.workspace.removeItem(it.id) }
            graph.workspace.addWidget(id, provider.flattenToString(), 0, x, y, spanX, spanY)
                ?: error("$provider could not be placed at ($x, $y)")
        }
    }

    /**
     * Waits for a widget's cell to compose. Through Compose, not UiAutomator: the composition's
     * frames only advance while the test drives them, so a UiAutomator wait alone would sit on a
     * screen that never changes.
     */
    protected fun awaitWidgetCell() {
        waitUntil(LONG_TIMEOUT_MS) {
            compose.onAllNodesWithTag(WIDGET_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** The first cell of the first home page nothing sits on. */
    protected fun freeHomeCell(columns: Int, pageRows: Int): Pair<Int, Int> {
        val taken = runBlocking {
            graph.workspace.observe(Container.HOME).first().pages.first().items.map {
                it.x to it.y
            }
        }
        return (0 until pageRows)
            .flatMap { y -> (0 until columns).map { x -> x to y } }
            .first { it !in taken }
    }

    /** Swipes the pages up and waits for the drawer to be all the way up, not merely on its way. */
    protected fun openDrawer() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeUp() }
        awaitDrawerOpen()
    }

    /**
     * The drawer is open and has arrived. It composes as soon as it starts up the screen, so a node
     * is not enough: a reveal too short to count falls back with the node there the whole way. The
     * view model's word says the gesture committed; its reveal runs on the composition's clock, so
     * idle then means it has arrived, and a swipe on a drawer still rising would be a pull, not a
     * scroll of its list.
     */
    protected fun awaitDrawerOpen() {
        waitUntil(TIMEOUT_MS) { compose.activity.viewModel.drawerOpen.value }
        compose.waitForIdle()
        compose.onNodeWithTag(DRAWER_TAG).assertIsDisplayed()
    }

    protected fun drawerApp(label: String) =
        compose.onNode(hasTestTag(DRAWER_ITEM_TAG) and hasContentDescription(label))

    /** The drawer's app tiles that are composed, which is its rows in view and a few beyond. */
    protected fun drawerItems() = compose.onAllNodesWithTag(DRAWER_ITEM_TAG).fetchSemanticsNodes()

    protected fun shownDrawerLabels() =
        drawerItems().map { it.config[SemanticsProperties.ContentDescription].single() }

    /** A long press on [node] without moving: the menu for it comes up. */
    protected fun hold(node: SemanticsNodeInteraction) {
        val start = node.fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(ITEM_MENU_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    /** A long press on [node] and a nudge past touch slop: the drag has begun, overlay showing. */
    protected fun lift(node: SemanticsNodeInteraction) =
        liftAt(node.fetchSemanticsNode().boundsInRoot.center)

    /** [lift] at [start] (root px). */
    protected fun liftAt(start: Offset) {
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

    protected fun awaitDrawerClosed(timeoutMs: Long = TIMEOUT_MS) {
        waitUntil(timeoutMs) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isEmpty()
        }
    }

    protected fun menuRow(text: String) =
        compose.onNode(
            hasText(text) and
                (hasAnyAncestor(hasTestTag(ITEM_MENU_TAG)) or
                    hasAnyAncestor(hasTestTag(HOME_MENU_TAG)))
        )

    /**
     * On a starved CI emulator the stock launcher can hang, and the system's "isn't responding"
     * dialog then sits over everything for the rest of the run, keeping focus from the launcher
     * under test. Closing that app clears it; the system starts it again when it is wanted.
     */
    private fun dismissNotRespondingDialog() {
        val close = device.findObject(By.text("Close app")) ?: return
        close.click()
        device.wait(Until.gone(By.text("Close app")), TIMEOUT_MS)
    }

    /** The window manager's word on what has focus, for a failure message. */
    private fun windowFocus(): String =
        device
            .executeShellCommand("dumpsys window")
            .lineSequence()
            .map { it.trim() }
            .filter { line ->
                line.startsWith("mCurrentFocus") ||
                    line.startsWith("mFocusedApp") ||
                    line.startsWith("mFocusedWindow") ||
                    line.contains("KeyguardShowing", ignoreCase = true)
            }
            .joinToString(" | ")
            .ifEmpty { "dumpsys window said nothing about focus" }

    /**
     * The seed on screen rather than the previous test's layout, which the grid's size alone cannot
     * tell apart: our app in the first home cell and the first dock app in the first slot, where
     * the seed puts them and a test that moved either left them elsewhere.
     */
    private fun awaitSeededLayout() {
        waitUntil(LONG_TIMEOUT_MS) {
            runCatching {
                    val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
                    val home = cellIcon(firstHomeApp).fetchSemanticsNode().boundsInRoot.center
                    val dock = cellIcon(firstDockApp).fetchSemanticsNode().boundsInRoot.center
                    grid.cellAt(home) == 0 to 0 && grid.dockSlotAt(dock) == 0
                }
                .getOrDefault(false)
        }
    }

    /**
     * Waits until the app icons on the pages are sized for a cell of a [columns] by [pageRows]
     * grid. Any app's icon will do: a test may have folded the first one away.
     */
    protected fun awaitGrid(columns: Int, pageRows: Int) {
        val described = labels.toSet()
        val appIcon =
            SemanticsMatcher("an app's icon") { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any {
                    it in described
                }
            }
        val onPages = hasAnyAncestor(hasTestTag(WORKSPACE_TAG)) and !hasTestTag(DRAG_OVERLAY_TAG)
        waitUntil(LONG_TIMEOUT_MS) {
            val page = compose.onAllNodesWithTag(WORKSPACE_TAG).fetchSemanticsNodes().firstOrNull()
            val icon =
                compose
                    .onAllNodes(appIcon and onPages, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .firstOrNull()
            if (page == null || icon == null) {
                false
            } else {
                val cell = minOf(page.size.width / columns, page.size.height / pageRows)
                abs(icon.size.width - cell * (1f - ICON_INSET)) <= GRID_TOLERANCE_PX
            }
        }
    }

    /** Screen geometry after switching to a grid, in root pixels. */
    /**
     * Where things are on screen, in root pixels, through the launcher's own drop geometry: a
     * [DropAreas] over the workspace and dock as drawn when the grid was made. Made at rest, that
     * is the layout before any drag zoom, which is what sizes are compared against; a grid made
     * mid-drag measures the zoomed areas, as the launcher itself does.
     */
    protected inner class Grid(val columns: Int, val pageRows: Int, val dockSlots: Int) {
        private val page = bounds(WORKSPACE_TAG)
        private val dock = bounds(DOCK_TAG)
        private val areas =
            DropAreas(
                home = page,
                columns = columns,
                rows = pageRows,
                dock = dock,
                dockSlots = dockSlots,
            )

        private fun bounds(tag: String) =
            compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.let {
                Bounds(it.left, it.top, it.right, it.bottom)
            }

        fun homeCell(x: Int, y: Int) = areas.centreOf(Container.HOME, Footprint(x, y))!!.offset

        fun dockSlot(slot: Int) = areas.centreOf(Container.DOCK, Footprint(slot, 0))!!.offset

        fun searchBar() = Offset((page.left + page.right) / 2, page.top / 2)

        /** The middle of the right edge zone, on [row]: a rest here flips the page. */
        fun rightEdge(row: Int) =
            Offset(page.right - edgeZone(page, columns) / 2, homeCell(0, row).y).also {
                check(areas.edgeAt(it.point) == EdgeHover(Container.HOME, Edge.RIGHT))
            }

        /** The middle of the dock's right or left edge zone. */
        fun dockRightEdge() =
            Offset(dock.right - edgeZone(dock, dockSlots) / 2, dockSlot(0).y).also {
                check(areas.edgeAt(it.point) == EdgeHover(Container.DOCK, Edge.RIGHT))
            }

        fun dockLeftEdge() =
            Offset(dock.left + edgeZone(dock, dockSlots) / 2, dockSlot(0).y).also {
                check(areas.edgeAt(it.point) == EdgeHover(Container.DOCK, Edge.LEFT))
            }

        private fun edgeZone(area: Bounds, across: Int) = area.width / across * EDGE_CELL_FRACTION

        fun dockSlotWidth() = dock.width / dockSlots

        fun cellWidth() = page.width / columns

        fun cellHeight() = page.height / pageRows

        /** The dock slot a root-pixel point in the dock falls in. */
        fun dockSlotAt(p: Offset) =
            (areas.cellUnder(p.point, zone = 1f) as DropTarget.DockSlot).slot

        /** The home cell a root-pixel point on the page falls in. */
        fun cellAt(p: Offset) = areas.homeCellAt(p.point)!!.let { it.x to it.y }
    }

    protected fun useGrid(columns: Int, rows: Int, dockSlots: Int = settings.dockSlots): Grid {
        val wanted = settings.copy(columns = columns, rows = rows, dockSlots = dockSlots)
        runBlocking { graph.settings.update { wanted } }
        awaitGrid(wanted.columns, wanted.pageRows)
        return Grid(wanted.columns, wanted.pageRows, wanted.dockSlots)
    }

    /** A long press on [from]'s icon, then a drag to [to], all in root coordinates. */
    protected fun drag(from: String, to: Offset) {
        holdDrag(from, to)
        release()
    }

    /** Like [drag] but leaves the finger down at [to]. */
    protected fun holdDrag(from: String, to: Offset) =
        holdDragAt(icon(from).fetchSemanticsNode().boundsInRoot.center, to)

    /** A long press at [start] (root px), then a drag to [to], finger left down. */
    protected fun holdDragAt(start: Offset, to: Offset) {
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
            fun icon(): SemanticsNode {
                val overlay =
                    compose
                        .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .firstOrNull()
                return overlay ?: cellIcon(label).fetchSemanticsNode()
            }
            // Measured against the destination cell where it is drawn that frame: the home area
            // is still zooming back out, so the cell itself moves a little.
            fun gap(): Float {
                val cell = cellIcon(label).fetchSemanticsNode().boundsInRoot.center
                return (icon().boundsInRoot.center - cell).getDistance()
            }
            fun carried() =
                compose
                    .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            compose.mainClock
                .advanceTimeByFrame() // the first held frame is still the pre-release one
            assertTrue("nothing was being carried when released", carried())
            var distance = gap()
            var width = icon().boundsInRoot.width
            android.util.Log.d(
                "RunwaySettle",
                "frame 1: ${icon().boundsInRoot.center} distance $distance width $width",
            )
            repeat(SETTLE_FRAMES) { frame ->
                compose.mainClock.advanceTimeByFrame()
                // The overlay may only go once it has all but arrived: a cut to the cell from
                // anywhere further off would read as "closer" too.
                if (!carried()) {
                    assertTrue("cut to the cell from $distance px away", distance <= JUMP_CUT_PX)
                }
                val bounds = icon().boundsInRoot
                val now = gap()
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
        // And, once everything has come to rest, it is in the slot it was aimed at. The home area
        // is zooming back out meanwhile, on a spring a slow runner draws late: wait for the icon to
        // arrive rather than reading it once.
        fun rest() = cellIcon(label).fetchSemanticsNode().boundsInRoot.center
        val arrived = runCatching {
            waitUntil(TIMEOUT_MS) { (rest() - slotCentre).getDistance() < SETTLE_REST_PX }
        }
        assertTrue("came to rest at ${rest()}, not $slotCentre", arrived.isSuccess)
    }

    /**
     * The HOME intent, addressed explicitly: the test install resets the device's default home app,
     * so the HOME key would open the stock launcher instead.
     */
    protected fun sendHomeIntent() {
        app.startActivity(
            Intent(Intent.ACTION_MAIN, null, app, LauncherActivity::class.java)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /**
     * The drop after a release has fully played out: the overlay is gone, the icon has settled, and
     * a move the drop planned has been written and is on screen. Idling alone waits on none of
     * that, and the database only on the coordinator's word.
     */
    protected fun awaitDropSettled() {
        awaitGone(DRAG_OVERLAY_TAG)
        val dragging = compose.activity.viewModel.dragging
        waitUntil(TIMEOUT_MS) { dragging.pending.value == null && dragging.settling.value == null }
    }

    /** [label] is still in the first home cell once the drop has fully played out. */
    protected fun assertUnmoved(label: String) {
        awaitDropSettled()
        assertEquals(0 to 0, homeCellOf(label))
        assertEquals(Container.HOME, placementOf(label)?.container)
    }

    /**
     * Taps [node], trying again if the device was too busy to take the tap: right after a drop a
     * slow emulator can refuse injected input ("Failed to inject touch input") for a moment.
     */
    protected fun tap(node: SemanticsNodeInteraction) {
        var attempt = 0
        while (true) {
            try {
                node.performClick()
                return
            } catch (e: AssertionError) {
                if (++attempt == TAP_ATTEMPTS || "inject" !in e.message.orEmpty()) throw e
                compose.waitForIdle()
                SystemClock.sleep(WRITE_GRACE_MS)
            }
        }
    }

    /** Waits until no node carries [tag]. */
    /**
     * [compose]'s `waitUntil`, except that a moment with no composition at all counts as "not yet"
     * rather than an error. The previous test's teardown starts a bootstrap activity of the test
     * package; on a slow device it can land on top of this test's launcher seconds later, and the
     * launcher is finished and recreated under the test. Its state comes back with it.
     */
    protected fun waitUntil(timeoutMillis: Long = TIMEOUT_MS, condition: () -> Boolean) {
        compose.waitUntil(timeoutMillis) {
            try {
                condition()
            } catch (e: IllegalStateException) {
                if (e.message?.startsWith(NO_COMPOSITION) == true) false else throw e
            }
        }
    }

    protected fun awaitGone(tag: String) {
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
    }

    /**
     * A pull that shows the drawer without opening it: well past touch slop, short of the default
     * setting's threshold. A screen so short that no pull fits between the two skips the test.
     */
    protected fun partialPullPx(): Float {
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        val opens =
            compose.onRoot().fetchSemanticsNode().boundsInRoot.height * DrawerSwipe.MEDIUM.openAt
        val pull = maxOf((slop + opens) / 2, slop * PAST_SLOP)
        assumeTrue("a $pull px pull would open the drawer at $opens px", pull < opens)
        return pull
    }

    /** Every placement of [label], across home and dock. */
    protected fun placementsOf(label: String): List<ItemEntity> = runBlocking {
        val ref = apps.first { it.label == label }.ref
        listOf(Container.HOME, Container.DOCK)
            .flatMap { graph.workspace.observe(it).first().pages }
            .flatMap { it.items }
            .filter { it.component == ref.component && it.profile == ref.profile }
    }

    /**
     * The settings screen has come up, after a tap on our icon or a menu's "Settings". A cold
     * activity launch on a loaded runner can take many seconds, so the long wait.
     */
    protected fun awaitSettingsOpen() {
        assertTrue("settings did not open", device.wait(Until.hasObject(SETTINGS), LONG_TIMEOUT_MS))
    }

    /**
     * Opens the settings page listed as [title]. The Compose rule owns the frame clock of every
     * composition in the process, the settings screen's too: nothing there moves after a
     * UiAutomator tap until the rule idles.
     */
    protected fun openSettingsPage(title: String) {
        assertTrue(
            "no $title in settings",
            device.wait(Until.hasObject(By.text(title)), TIMEOUT_MS),
        )
        device.findObject(By.text(title)).click()
        compose.waitForIdle()
        assertTrue(
            "the $title page did not open",
            device.wait(Until.hasObject(SETTINGS_PAGE_BACK), TIMEOUT_MS),
        )
    }

    /**
     * Backs out of settings from the page that is open, page by page to the list and then out, so
     * the next test to open settings finds it on its list.
     */
    protected fun leaveSettings() {
        var depth = 0
        while (depth < MAX_SETTINGS_DEPTH && device.hasObject(SETTINGS_PAGE_BACK)) {
            device.pressBack()
            compose.waitForIdle()
            depth++
        }
        assertTrue(
            "back did not reach the list",
            device.wait(Until.gone(SETTINGS_PAGE_BACK), TIMEOUT_MS),
        )
        device.pressBack()
    }

    /** The lifted icon is our settings app; a stray click would open its list of pages. */
    protected fun assertStillOnLauncher() {
        compose.waitForIdle()
        assertTrue("a drag must not also launch the app", !device.hasObject(SETTINGS))
    }

    /** The placement of [label], by component and profile, so a work-profile twin is its own. */
    protected fun placementOf(label: String): ItemEntity? = placementsOf(label).firstOrNull()

    protected fun dockPageCount() = runBlocking {
        graph.workspace.observe(Container.DOCK).first().pages.size
    }

    protected fun labelOnPage(page: Int): String = runBlocking {
        labelOf(
            graph.workspace
                .observe(Container.HOME)
                .first()
                .pages
                .first { it.index == page }
                .items
                .first()
        )
    }

    protected fun labelAtDockSlot(slot: Int): String = runBlocking {
        labelOf(
            graph.workspace.observe(Container.DOCK).first().pages.first().items.first {
                it.x == slot
            }
        )
    }

    protected fun labelAtHomeCell(x: Int, y: Int): String = runBlocking {
        labelOf(
            graph.workspace.observe(Container.HOME).first().pages.first().items.first {
                it.x == x && it.y == y
            }
        )
    }

    /** The label of the app placed as [item]. */
    protected fun labelOf(item: ItemEntity): String =
        apps.first { it.ref.component == item.component }.label

    /** The open folder's app labels, read off the sheet in the order its tiles are laid out. */
    protected fun shownFolderApps(): List<String> =
        compose
            .onAllNodesWithTag(FOLDER_ITEM_TAG)
            .fetchSemanticsNodes()
            .sortedWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
            .mapNotNull {
                it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            }

    /** [labels] in the order the drawer lists them, which is a folder's until a hand orders it. */
    protected fun inDrawerOrder(vararg labels: String): List<String> =
        labels.sortedWith(LabelOrder.comparator())

    /**
     * The labels of the apps in the folder at home cell ([x], [y]) on page 1, as the folder lists
     * them; null if no folder.
     */
    protected fun folderAt(x: Int, y: Int): List<String>? =
        folderLabels(Container.HOME) { it.x == x && it.y == y }

    /** The labels of the apps in the folder in dock slot [slot] on dock page 1; null if none. */
    protected fun dockFolderAt(slot: Int): List<String>? =
        folderLabels(Container.DOCK) { it.x == slot }

    private fun folderLabels(container: Container, at: (ItemEntity) -> Boolean) = runBlocking {
        val folder =
            graph.workspace.observe(container).first().pages.first().items.firstOrNull {
                it.kind == ItemKind.FOLDER && at(it)
            }
        folder?.let { item ->
            val apps = graph.appRepository.apps.first { it.isNotEmpty() }
            val content = graph.workspace.observeFolders().first().first { it.id == item.folderId }
            val labels = content.apps.map { ref -> apps.first { it.ref == ref }.label }
            // As the sheet shows them: its hand order, or the drawer's.
            if (content.handSorted) labels else labels.sortedWith(LabelOrder.comparator())
        }
    }

    protected fun homeCellOf(label: String): Pair<Int?, Int?>? =
        placementOf(label)?.takeIf { it.container == Container.HOME }?.let { it.x to it.y }

    /**
     * The app's icon on the home pages or in the dock, or the overlay's copy of it while it is
     * being dragged: two nodes then, so a drag's questions go to [cellIcon] or the overlay. Icons
     * live inside clickable cells, whose semantics merge, so the unmerged tree is searched. Not the
     * search bar's glass, which is described by the search app's name, and not the drawer's, an
     * open folder's or the item menu's copies, which have lookups of their own.
     */
    protected fun icon(label: String) =
        compose.onNode(hasContentDescription(label) and onTheHomeScreen(), useUnmergedTree = true)

    /** The icon in its cell, even while a copy of it is being dragged in the overlay. */
    protected fun cellIcon(label: String) =
        compose.onNode(
            hasContentDescription(label) and !hasTestTag(DRAG_OVERLAY_TAG) and onTheHomeScreen(),
            useUnmergedTree = true,
        )

    private fun onTheHomeScreen() =
        !hasTestTag(SEARCH_TARGET_ICON_TAG) and
            !hasAnyAncestor(hasTestTag(DRAWER_TAG)) and
            !hasAnyAncestor(hasTestTag(FOLDER_TAG)) and
            !hasAnyAncestor(hasTestTag(ITEM_MENU_TAG))
}

/**
 * Displayed, or not: absent counts as not displayed, but a lookup that matches more than one node
 * is a question with no answer and fails as such, rather than passing as "not shown".
 */
fun SemanticsNodeInteraction.isDisplayedOrFalse(): Boolean =
    try {
        assertIsDisplayed()
        true
    } catch (e: AssertionError) {
        val message = e.message.orEmpty()
        if ("is not displayed" in message || "could not find any node" in message) {
            false
        } else {
            throw e
        }
    }

const val TIMEOUT_MS = 5_000L

/** The fixture app's widget: two by one by design, resizable, labelled "Fixture widget". */
val FIXTURE_WIDGET: ComponentName =
    ComponentName("com.grayvines.runway.fixture", "com.grayvines.runway.fixture.FixtureWidget")

/** The fixture app's widget with a setup screen, which it takes again once placed. */
val FIXTURE_SETUP_WIDGET: ComponentName =
    ComponentName(
        "com.grayvines.runway.fixture",
        "com.grayvines.runway.fixture.FixtureSetupWidget",
    )

/** The Done button on the fixture's widget setup screen. */
const val FIXTURE_SETUP_DONE = "Done"

/** Focus waits per test: one, and one more after closing a dialog that came up meanwhile. */
const val FOCUS_ATTEMPTS = 2

/** Whether [LauncherFixture] has already waited out the device's boot in this process. */
private var deviceWentQuiet = false

/** A boot's tail was seen to run a minute into the first test on a CI runner. */
const val QUIET_TIMEOUT_MS = 90_000L
const val QUIET_SAMPLE_MS = 1_000L
const val QUIET_SAMPLES = 3
const val QUIET_IDLE_FRACTION = 0.5f

/** Where idle time sits among the numbers on the summary line of `/proc/stat`. */
const val IDLE_COLUMN = 3

/** How Compose's test rule words a moment with no composition anywhere in the process. */
const val NO_COMPOSITION = "No compose hierarchies found"

/**
 * The stand-in app Gradle installs beside the launcher for these tests (the fixture module): the
 * one app that can always be uninstalled, and always a web-search handler.
 */
const val FIXTURE_PACKAGE = "com.grayvines.runway.fixture"
const val LONG_PRESS_MS = 1_000L

/** Real time past a dwell and the scroll it starts, for a runner that is slow to draw either. */
const val DWELL_MARGIN_MS = 900L
/** Longer than the dwell that adds a page, in real time. */
const val EDGE_ADD_MS = EdgeDwell.ADD_PAGE_MS + FLIP_SCROLL_MS + DWELL_MARGIN_MS
/** Longer than the dwell that flips a page, in real time. */
const val EDGE_FLIP_MS = EdgeDwell.FLIP_MS + FLIP_SCROLL_MS + DWELL_MARGIN_MS
const val FLIP_SAMPLE_MS = 30L
const val FLIP_WATCH_MS = 2_500L

/** The launcher's own hold, under the name the tests in this package have always used. */
const val LIFT_HOLD_MS = com.grayvines.runway.ui.home.LIFT_HOLD_MS

/** Past touch slop: enough movement after a hold to turn it into a drag. */
const val LIFT_NUDGE_PX = 60f

/** How often a refused tap is tried before giving up. */
const val TAP_ATTEMPTS = 3

/** A slow pull, in steps far enough apart not to count as a flick. */
const val PULL_STEPS = 10
const val PULL_STEP_MS = 40L

/** Of the screen height: well past the third of the way that opens the drawer or the shade. */
const val OPENING_PULL = 0.35f

/** Long enough for a shade that was going to come down. */
const val SHADE_GRACE_MS = 1_000L

/** The system's notification shade, once down. */
val SHADE: BySelector = By.res("com.android.systemui", "notification_stack_scroller")

/** The first row of settings' list of pages, which is where settings opens. */
val SETTINGS: BySelector = By.text("Home screen")

/** The way back to the list, on every settings page but the list itself. */
val SETTINGS_PAGE_BACK: BySelector = By.desc("Back")

/** The most pages deep settings goes: the list, a page, and one opened from that. */
const val MAX_SETTINGS_DEPTH = 2

/** Icon sizes are rounded to pixels on the way; this much slack covers it. */
const val GRID_TOLERANCE_PX = 2f

/** How long a database write that was going to happen takes to show up. */
const val WRITE_GRACE_MS = 500L

/** Of a cell's width from its middle: just outside the middle share where a drop folds. */
const val BESIDE = FOLD_ZONE / 2 + 0.05f

/** Outside even the wider zone a fold, once begun, keeps to, so beside by any measure. */
const val WELL_BESIDE = FOLD_KEEP_ZONE / 2

private val Point.offset: Offset
    get() = Offset(x, y)

private val Offset.point: Point
    get() = Point(x, y)
const val FRAME_MS = 16L
const val LIFT_FRAMES = 60
const val DRAG_STEPS = 10
const val DRAG_STEP_MS = 30L
const val LIFT_ANIMATION_MS = 1_000L
const val LONG_TIMEOUT_MS = 15_000L
const val PRESS_SETTLE_MS = 250L
const val SETTLE_FRAMES = 24
const val SETTLE_TOLERANCE_PX = 2f
const val SETTLE_REST_PX = 4f

/** How far from its cell a settling icon may still be when the overlay hands over to the cell. */
const val JUMP_CUT_PX = 2 * SETTLE_REST_PX

/** A partial pull is at least this many touch slops, so that it is a move on any screen. */
const val PAST_SLOP = 2f
