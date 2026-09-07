package com.grayvines.runway

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.ui.drawer.DRAWER_ITEM_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import com.grayvines.runway.ui.home.DRAG_OVERLAY_TAG
import com.grayvines.runway.ui.home.SEARCH_BAR_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app drawer: opening, what it lists, launching, and every way of closing it. */
@RunWith(AndroidJUnit4::class)
class DrawerTest : LauncherFixture() {
    @Test
    fun swipingUpOnThePagesOpensTheDrawerListingEveryAppAlphabetically() {
        val labels = runBlocking {
            graph.appRepository.apps
                .first { it.isNotEmpty() }
                .map { it.label }
                .sortedWith(String.CASE_INSENSITIVE_ORDER)
        }
        openDrawer()
        compose
            .onAllNodesWithTag(DRAWER_ITEM_TAG)
            .onFirst()
            .assertContentDescriptionEquals(labels.first())
        compose.onNodeWithTag(DRAWER_TAG).performScrollToNode(hasContentDescription(labels.last()))
        drawerApp(labels.last()).assertIsDisplayed()
    }

    @Test
    fun tappingAnAppInTheDrawerLaunchesItAndClosesTheDrawer() {
        openDrawer()
        compose.onNodeWithTag(DRAWER_TAG).performScrollToNode(hasContentDescription(firstHomeApp))
        drawerApp(firstHomeApp).performClick()
        assertTrue(
            "settings did not open",
            device.wait(Until.hasObject(By.text("Grid")), TIMEOUT_MS),
        )
        device.pressBack()
        awaitDrawerClosed()
    }

    @Test
    fun backClosesTheDrawer() {
        openDrawer()
        device.pressBack()
        awaitDrawerClosed()
    }

    @Test
    fun pullingTheListDownPastTheTopClosesTheDrawer() {
        openDrawer()
        compose.onNodeWithTag(DRAWER_TAG).performTouchInput { swipeDown() }
        awaitDrawerClosed()
    }

    @Test
    fun theHomeIntentClosesTheDrawerAndStaysOnTheCurrentPage() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeLeft() }
        compose.waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }
        openDrawer()
        sendHomeIntent()
        awaitDrawerClosed()
        assertTrue(
            "HOME with the drawer open must not also change page",
            !icon(firstHomeApp).isDisplayedOrFalse(),
        )
    }

    @Test
    fun aPartialSwipeUpRevealsTheDrawerAndLettingGoHidesItAgain() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, -root.height * PARTIAL_PULL / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
        }
        // Part way: present, but not yet settled onto the screen.
        val drawer = compose.onNodeWithTag(DRAWER_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("drawer at ${drawer.top} should still be arriving", drawer.top > root.top + 1f)
        compose.onRoot().performTouchInput { up() }
        awaitDrawerClosed()
    }

    @Test
    fun lettingGoPastAThirdOfTheScreenOpensTheDrawerTheRestOfTheWay() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, -root.height * OPENING_PULL / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            up()
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().firstOrNull()?.let {
                abs(it.boundsInRoot.top - root.top) < 1f
            } ?: false
        }
        device.pressBack()
        awaitDrawerClosed()
    }

    @Test
    fun theSwipeSensitivitySettingDecidesWhatAModestPullDoes() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        fun pull() =
            compose.onRoot().performTouchInput {
                down(pages.center)
                repeat(PULL_STEPS) {
                    moveBy(Offset(0f, -root.height * MODEST_PULL / PULL_STEPS))
                    advanceEventTime(PULL_STEP_MS)
                }
                up()
            }
        runBlocking { graph.settings.update { it.copy(drawerSwipe = DrawerSwipe.LOW) } }
        compose.waitForIdle()
        pull()
        awaitDrawerClosed() // a tenth of the screen is not enough on Low
        runBlocking { graph.settings.update { it.copy(drawerSwipe = DrawerSwipe.HIGH) } }
        compose.waitForIdle()
        pull()
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().firstOrNull()?.let {
                abs(it.boundsInRoot.top - root.top) < 1f
            } ?: false
        }
    }

    @Test
    fun anAppPulledOutOfTheDrawerLandsOnAFreeCellAndTheDrawerCloses() {
        val grid = useGrid(columns = 5, rows = 7)
        val label = firstDrawerLabel()
        openDrawer()
        liftFromDrawer(label)
        dragOn(to = grid.homeCell(4, 4))
        awaitDrawerClosed() // it went as soon as the app lifted
        release()
        compose.waitUntil(TIMEOUT_MS) { placementsOf(label).any { it.x == 4 && it.y == 4 } }
    }

    @Test
    fun anAppPulledOutOfTheDrawerOntoATakenDockSlotAddsNothing() {
        val grid = useGrid(columns = 5, rows = 7)
        val label = firstDrawerLabel()
        val before = placementsOf(label).size
        openDrawer()
        liftFromDrawer(label)
        dragOn(to = grid.dockSlot(0))
        release()
        awaitDrawerClosed()
        compose.waitForIdle()
        assertEquals(before, placementsOf(label).size)
    }

    private fun firstDrawerLabel() = runBlocking {
        graph.appRepository.apps
            .first { it.isNotEmpty() }
            .map { it.label }
            .sortedWith(String.CASE_INSENSITIVE_ORDER)
            .first()
    }

    /** Long-presses [label] in the open drawer and nudges it, so the drag has begun. */
    private fun liftFromDrawer(label: String) {
        val start = drawerApp(label).fetchSemanticsNode().boundsInRoot.center
        compose.onRoot().performTouchInput { down(start) }
        compose.mainClock.advanceTimeBy(LIFT_HOLD_MS + FRAME_MS)
        compose.onRoot().performTouchInput { moveBy(Offset(0f, -LIFT_NUDGE_PX)) }
        compose.waitUntil(TIMEOUT_MS) {
            compose
                .onAllNodesWithTag(DRAG_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun placementsOf(label: String) = runBlocking {
        val component =
            graph.appRepository.apps
                .first { it.isNotEmpty() }
                .first { it.label == label }
                .ref
                .component
        listOf(Container.HOME, Container.DOCK)
            .flatMap { graph.workspace.observe(it).first().pages }
            .flatMap { it.items }
            .filter { it.component == component }
    }

    @Test
    fun aQuickShortSwipeOpensTheDrawer() {
        // Fast enough that the drawer's animation cannot keep up with the finger, and short: what
        // a real thumb does. It used to read as "no pull" and fall back.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(3) {
                moveBy(Offset(0f, -root.height * QUICK_SWIPE / 3))
                advanceEventTime(QUICK_STEP_MS)
            }
            up()
        }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().firstOrNull()?.let {
                abs(it.boundsInRoot.top - root.top) < 1f
            } ?: false
        }
    }

    @Test
    fun aSwipeUpFromTheSearchBarOpensTheDrawerToo() {
        compose.onNodeWithTag(SEARCH_BAR_TAG).performTouchInput { swipeUp() }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun openDrawer() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeUp() }
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitDrawerClosed() {
        compose.waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun drawerApp(label: String) =
        compose.onNode(hasTestTag(DRAWER_ITEM_TAG) and hasContentDescription(label))

    private companion object {
        const val PULL_STEPS = 10
        const val QUICK_SWIPE = 0.08f
        const val QUICK_STEP_MS = 16L
        const val PULL_STEP_MS = 40L // slow enough not to count as a flick
        const val PARTIAL_PULL = 0.01f // under Medium's 2%: shows the drawer, lets it fall back
        const val OPENING_PULL = 0.35f // well past a third of the pull distance
        const val MODEST_PULL = 0.05f // between High's 1% and Low's 8%
    }
}
