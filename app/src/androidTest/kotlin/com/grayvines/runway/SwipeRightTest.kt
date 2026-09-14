package com.grayvines.runway

import android.os.SystemClock
import android.view.ViewConfiguration
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.SwipeAction
import com.grayvines.runway.ui.drawer.REVERSAL_DP
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A swipe right on the first home page, and the app it is set to open. */
@RunWith(AndroidJUnit4::class)
class SwipeRightTest : LauncherFixture() {
    private val fixture
        get() = apps.first { it.component.packageName == FIXTURE_PACKAGE }

    @Test
    fun aFlickRightOnTheFirstPageOpensTheChosenApp() {
        chooseToOpen(fixture.ref)
        flick { swipeRight() }
        assertOpened()
    }

    @Test
    fun aSlowSwipeRightPastTheDistanceOpensTheChosenApp() {
        chooseToOpen(fixture.ref)
        slowSwipe(opensAtPx() * FAR)
        assertOpened()
    }

    @Test
    fun aSlowSwipeRightShortOfTheDistanceOpensNothing() {
        chooseToOpen(fixture.ref)
        slowSwipe(opensAtPx() * SHORT)
        assertNothingOpened()
    }

    @Test
    fun aSwipeRightThatComesBackBeforeLettingGoOpensNothing() {
        chooseToOpen(fixture.ref)
        // Still well past the distance when it lets go, but back from the farthest it got.
        slowSwipe(opensAtPx() * FAR, -REVERSAL_DP * app.resources.displayMetrics.density * 2)
        assertNothingOpened()
    }

    @Test
    fun aSwipeRightTakenAwayByTheSystemOpensNothing() {
        chooseToOpen(fixture.ref)
        // What a back gesture from the screen's edge does to the touch it takes.
        slowSwipe(opensAtPx() * FAR) { cancel() }
        assertNothingOpened()
    }

    @Test
    fun withNothingChosen_aSwipeRightOnTheFirstPageOpensNothing() {
        flick { swipeRight() }
        assertNothingOpened()
        assertTrue("left the first page", icon(firstHomeApp).isDisplayedOrFalse())
    }

    @Test
    fun aChosenAppThatIsNotInstalledOpensNothing() {
        chooseToOpen(AppRef("$FIXTURE_PACKAGE.gone/.Main", fixture.ref.profile))
        flick { swipeRight() }
        assertNothingOpened()
    }

    @Test
    fun aSwipeRightOnALaterPageGoesBackAPageAndOpensNothing() {
        chooseToOpen(fixture.ref)
        flick { swipeLeft() }
        waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }
        flick { swipeRight() }
        waitUntil(TIMEOUT_MS) { icon(firstHomeApp).isDisplayedOrFalse() }
        assertNothingOpened()
    }

    @Test
    fun aSwipeLeftOnTheFirstPageGoesToTheNextPageAndOpensNothing() {
        chooseToOpen(fixture.ref)
        flick { swipeLeft() }
        waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }
        assertNothingOpened()
    }

    @Test
    fun anIconHeldAndDraggedRightOpensNothing() {
        chooseToOpen(fixture.ref)
        val grid = Grid(settings.columns, settings.pageRows, settings.dockSlots)
        drag(from = firstHomeApp, to = grid.homeCell(settings.columns - 1, 1))
        awaitDropSettled()
        assertNothingOpened()
        assertStillOnLauncher()
    }

    @Test
    fun pickingAnAppInSettingsSetsTheSwipe_andNothingClearsIt() {
        // Not an app whose name the settings screen shows already: the search target's button, or
        // our own app's title.
        val searchTarget = graph.searchTargets.resolve(null)?.label
        val picked = apps.first {
            it.component.packageName != app.packageName && it.label != searchTarget
        }
        icon(firstHomeApp).performClick()
        awaitSettingsOpen()

        val nothing = By.text("Nothing")
        scrollSettingsTo(nothing)
        chooseInSettings(opener = nothing, choice = By.text(picked.label))
        waitUntil(TIMEOUT_MS) { swipeRightSetting() == SwipeAction.OpenApp(picked.ref) }

        // The button now names the app; the list it opens starts with Nothing.
        chooseInSettings(opener = By.text(picked.label), choice = nothing)
        waitUntil(TIMEOUT_MS) { swipeRightSetting() == null }
        device.pressBack()
    }

    /**
     * Taps [opener] on the settings screen, waits for the list of apps, and taps [choice] in it.
     * The Compose rule owns the frame clock of every composition in the process, the settings
     * screen's too: nothing there moves after a UiAutomator tap until the rule idles.
     */
    private fun chooseInSettings(
        opener: BySelector,
        choice: BySelector,
    ) {
        assertTrue("no $opener in settings", device.wait(Until.hasObject(opener), TIMEOUT_MS))
        device.findObject(opener).click()
        compose.waitForIdle()
        assertTrue(
            "the list of apps did not open",
            device.wait(Until.hasObject(By.text("Swipe right opens")), TIMEOUT_MS),
        )
        device.findObject(choice).click()
        compose.waitForIdle()
    }

    private fun swipeRightSetting() = runBlocking { graph.settings.settings.first().swipeRight }

    /** The swipe is set to open [ref], and the home screen knows it. */
    private fun chooseToOpen(ref: AppRef) {
        val action = SwipeAction.OpenApp(ref)
        runBlocking { graph.settings.update { it.copy(swipeRight = action) } }
        waitUntil(TIMEOUT_MS) {
            compose.activity.viewModel.state.value.settings.swipeRight == action
        }
    }

    private fun flick(gesture: TouchInjectionScope.() -> Unit) {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput(gesture)
        compose.waitForIdle()
    }

    /** What the default sensitivity asks of a swipe past touch slop (px): the drawer's distance. */
    private fun opensAtPx() =
        compose.onRoot().fetchSemanticsNode().boundsInRoot.height * DrawerSwipe.MEDIUM.openAt

    /**
     * A finger down a quarter of the way across the first page crosses touch slop at once (a finger
     * resting inside it would hold what is under it), goes on by each of [legs] in turn (px,
     * positive right) in steps far enough apart that letting go carries no speed, and then [end]s.
     *
     * One injection from down to [end]: the screen never settles while a finger holds the pages
     * past their first page, so waiting for it with the finger still down waits for good.
     */
    private fun slowSwipe(vararg legs: Float, end: TouchInjectionScope.() -> Unit = { up() }) {
        val page = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        val start = Offset(page.left + page.width / 4, page.center.y)
        val slop = ViewConfiguration.get(app).scaledTouchSlop
        compose.onRoot().performTouchInput {
            down(start)
            moveBy(Offset(slop + 1f, 0f))
            legs.forEach { dx ->
                repeat(SLOW_STEPS) {
                    advanceEventTime(SLOW_STEP_MS)
                    moveBy(Offset(dx / SLOW_STEPS, 0f))
                }
            }
            advanceEventTime(SLOW_STEP_MS)
            end()
        }
    }

    private fun assertOpened() {
        waitUntil(LONG_TIMEOUT_MS) { device.currentPackageName == FIXTURE_PACKAGE }
        sendHomeIntent()
        waitUntil(LONG_TIMEOUT_MS) { device.currentPackageName == app.packageName }
    }

    /** Given the time an app takes to come up on a slow device, nothing did. */
    private fun assertNothingOpened() {
        compose.waitForIdle()
        SystemClock.sleep(OPEN_GRACE_MS)
        assertEquals("the swipe opened something", app.packageName, device.currentPackageName)
    }

    private companion object {
        /** Of the distance: well past it, and well short of it. */
        const val FAR = 4f
        const val SHORT = 0.3f

        /** Steps further apart than a velocity tracker still counts as one movement. */
        const val SLOW_STEPS = 8
        const val SLOW_STEP_MS = 100L

        const val OPEN_GRACE_MS = 1_500L
    }
}
