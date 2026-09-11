package com.grayvines.runway

import android.content.ComponentName
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ComposeTimeoutException
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
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.addWidget
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.LabelOrder
import com.grayvines.runway.ui.drawer.DRAWER_ITEM_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import com.grayvines.runway.ui.home.DOCK_TAG
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.ICON_INSET
import com.grayvines.runway.ui.home.SEARCH_TARGET_ICON_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import com.grayvines.runway.ui.menu.HOME_MENU_TAG
import com.grayvines.runway.ui.menu.ITEM_MENU_TAG
import com.grayvines.runway.ui.widgets.WIDGET_TAG
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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

    /**
     * The apps tests may refer to by label: those with a label of their own, sorted as the drawer
     * sorts.
     */
    protected lateinit var apps: List<AppEntry>

    protected val labels: List<String>
        get() = apps.map { it.label }

    @Before
    fun seed() {
        runBlocking {
            graph.settings.update { settings }
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
        // Touches injected before the window has focus are refused ("Failed to inject touch
        // input"): the previous test's activity may still be on its way out on a slow device.
        awaitWindowFocus()
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

    private fun dismissKeyboard() {
        val window = compose.activity.window
        compose.runOnUiThread {
            window.currentFocus?.clearFocus()
            WindowCompat.getInsetsController(window, window.decorView).hide(Type.ime())
        }
        // The insets say "hidden" as soon as the hide is asked for; the keyboard's own window is
        // still on its way out, and a window torn down under it leaves the system bringing the
        // keyboard back for the next one. Wait for the keyboard itself to have gone.
        val keyboard = keyboardPackage()
        waitUntil(TIMEOUT_MS) {
            ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(Type.ime()) != true &&
                (keyboard == null || !device.hasObject(By.pkg(keyboard)))
        }
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
        InstrumentationRegistry.getInstrumentation()
            .uiAutomation
            .executeAndWaitForEvent(
                action,
                { event ->
                    event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
                        event.text.any { it.toString() == text }
                },
                TIMEOUT_MS,
            )
    }

    /**
     * Binds the fixture's widget the way the picker does and puts it over [spanX] × [spanY] cells
     * of the first page from ([x], [y]), whose icons make way; the placement's id.
     */
    protected fun placeFixtureWidget(x: Int, y: Int, spanX: Int = 2, spanY: Int = 1): Long {
        allowWidgetBinding(true)
        val id = graph.widgets.allocateId()
        assertTrue(
            "could not bind the fixture widget",
            graph.widgets.bind(id, FIXTURE_WIDGET, Process.myUserHandle()),
        )
        return runBlocking {
            val page = graph.workspace.observe(Container.HOME).first().pages.first()
            page.items
                .filter { it.x in x until x + spanX && it.y in y until y + spanY }
                .forEach { graph.workspace.removeItem(it.id) }
            graph.workspace.addWidget(id, FIXTURE_WIDGET.flattenToString(), 0, x, y, spanX, spanY)
                ?: error("the fixture widget could not be placed at ($x, $y)")
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
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // The drawer composes as soon as it starts up the screen. Its reveal runs on the
        // composition's clock, so idle means it has arrived, and a swipe on a drawer still rising
        // would be a pull, not a scroll of its list.
        compose.waitForIdle()
    }

    protected fun drawerApp(label: String) =
        compose.onNode(hasTestTag(DRAWER_ITEM_TAG) and hasContentDescription(label))

    /** A long press on [node] without moving: the menu for it comes up. */
    protected fun hold(node: SemanticsNodeInteraction) {
        val start = node.fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(ITEM_MENU_TAG).fetchSemanticsNodes().isNotEmpty()
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
     * Waits until the first home icon is the size the grid [columns] × [pageRows] gives it: the
     * sign that settings and layout have both reached the screen.
     */
    private fun awaitGrid(columns: Int, pageRows: Int) {
        waitUntil(LONG_TIMEOUT_MS) {
            val page = compose.onAllNodesWithTag(WORKSPACE_TAG).fetchSemanticsNodes().firstOrNull()
            val icon =
                icon(firstHomeApp).let { runCatching { it.fetchSemanticsNode() }.getOrNull() }
            if (page == null || icon == null) {
                false
            } else {
                val cell = minOf(page.size.width / columns, page.size.height / pageRows)
                abs(icon.size.width - cell * (1f - ICON_INSET)) <= GRID_TOLERANCE_PX
            }
        }
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
        runBlocking {
            graph.settings.update { it.copy(columns = columns, rows = rows, dockSlots = dockSlots) }
        }
        awaitGrid(columns, rows - Settings.RESERVED_ROWS)
        return Grid(columns, rows - Settings.RESERVED_ROWS, dockSlots)
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
            compose.mainClock
                .advanceTimeByFrame() // the first held frame is still the pre-release one
            var distance = gap()
            var width = icon().boundsInRoot.width
            android.util.Log.d(
                "RunwaySettle",
                "frame 1: ${icon().boundsInRoot.center} distance $distance width $width",
            )
            repeat(SETTLE_FRAMES) { frame ->
                compose.mainClock.advanceTimeByFrame()
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
        // And, once everything has come to rest, it is in the slot it was aimed at.
        compose.waitForIdle()
        val rest = cellIcon(label).fetchSemanticsNode().boundsInRoot.center
        assertTrue(
            "came to rest at $rest, not $slotCentre",
            (rest - slotCentre).getDistance() < SETTLE_REST_PX,
        )
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
     * [label] is still in the first home cell once the drop has fully played out: the lifted icon
     * has settled and a write that was going to land has had time to. Idling alone does not wait on
     * the database.
     */
    protected fun assertUnmoved(label: String) {
        awaitGone(DRAG_OVERLAY_TAG)
        SystemClock.sleep(WRITE_GRACE_MS)
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
     * A pull that shows the drawer without opening it: past touch slop, short of the default
     * setting's threshold, on any screen.
     */
    protected fun partialPullPx(): Float {
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        val opens =
            compose.onRoot().fetchSemanticsNode().boundsInRoot.height * DrawerSwipe.MEDIUM.openAt
        return (slop + opens) / 2
    }

    /** Every placement of [label], across home and dock. */
    protected fun placementsOf(label: String): List<ItemEntity> = runBlocking {
        val ref = apps.first { it.label == label }.ref
        listOf(Container.HOME, Container.DOCK)
            .flatMap { graph.workspace.observe(it).first().pages }
            .flatMap { it.items }
            .filter { it.component == ref.component && it.profile == ref.profile }
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

    /**
     * The labels of the apps in the folder at home cell ([x], [y]) on page 1; null if no folder.
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
            graph.workspace
                .observeFolders()
                .first()
                .first { it.id == item.folderId }
                .apps
                .map { ref -> apps.first { it.ref == ref }.label }
        }
    }

    protected fun homeCellOf(label: String): Pair<Int?, Int?>? =
        placementOf(label)?.takeIf { it.container == Container.HOME }?.let { it.x to it.y }

    protected fun labelToComponent(label: String): String = runBlocking {
        graph.appRepository.apps.first { it.isNotEmpty() }.first { it.label == label }.ref.component
    }

    /** Icons live inside clickable cells, whose semantics merge; look at the unmerged tree. */
    /**
     * The app's icon. Not the search bar's glass, which is described by the search app's name and
     * so shares a label with that app's icon.
     */
    protected fun icon(label: String) =
        compose.onNode(
            hasContentDescription(label) and !hasTestTag(SEARCH_TARGET_ICON_TAG),
            useUnmergedTree = true,
        )

    /** The icon in its cell, even while a copy of it is being dragged in the overlay. */
    protected fun cellIcon(label: String) =
        compose.onNode(
            hasContentDescription(label) and
                !hasTestTag(DRAG_OVERLAY_TAG) and
                !hasTestTag(SEARCH_TARGET_ICON_TAG),
            useUnmergedTree = true,
        )

    protected fun SemanticsNodeInteraction.isDisplayedOrFalse() = runCatching {
        assertIsDisplayed()
        true
    }
        .getOrDefault(false)
}

const val TIMEOUT_MS = 5_000L

/** The fixture app's widget: two by one by design, resizable, labelled "Fixture widget". */
val FIXTURE_WIDGET: ComponentName =
    ComponentName("com.grayvines.runway.fixture", "com.grayvines.runway.fixture.FixtureWidget")

/** Focus waits per test: one, and one more after closing a dialog that came up meanwhile. */
const val FOCUS_ATTEMPTS = 2

/** How Compose's test rule words a moment with no composition anywhere in the process. */
const val NO_COMPOSITION = "No compose hierarchies found"

/**
 * The stand-in app Gradle installs beside the launcher for these tests (the fixture module): the
 * one app that can always be uninstalled, and always a web-search handler.
 */
const val FIXTURE_PACKAGE = "com.grayvines.runway.fixture"
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

/** How often a refused tap is tried before giving up. */
const val TAP_ATTEMPTS = 3

/** Icon sizes are rounded to pixels on the way; this much slack covers it. */
const val GRID_TOLERANCE_PX = 2f

/** How long a database write that was going to happen takes to show up. */
const val WRITE_GRACE_MS = 500L

/** Of a cell's width from its middle: outside the middle 60% where a drop folds. */
const val BESIDE = 0.35f
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
