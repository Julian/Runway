package com.grayvines.runway

import android.os.SystemClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeUp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.Until
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.drawer.DRAWER_INDEX_TAG
import com.grayvines.runway.ui.drawer.DRAWER_ITEM_TAG
import com.grayvines.runway.ui.drawer.DRAWER_LIST_TAG
import com.grayvines.runway.ui.drawer.DRAWER_SEARCH_TAG
import com.grayvines.runway.ui.drawer.DRAWER_TAG
import com.grayvines.runway.ui.drawer.index
import com.grayvines.runway.ui.home.SEARCH_BAR_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import com.grayvines.runway.ui.shade.SHADE_HINT_TAG
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The app drawer: opening, what it lists, launching, and every way of closing it. */
@RunWith(AndroidJUnit4::class)
class DrawerTest : LauncherFixture() {
    @Test
    fun swipingUpOnThePagesOpensTheDrawerListingEveryAppAlphabetically() {
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
        awaitSettingsOpen()
        device.pressBack()
        awaitDrawerClosed()
    }

    @Test
    fun backClosesTheDrawer() {
        openDrawer()
        closeDrawerWithBack()
    }

    @Test
    fun pullingTheListDownPastTheTopClosesTheDrawer() {
        openDrawer()
        compose.onNodeWithTag(DRAWER_LIST_TAG).performTouchInput { swipeDown() }
        awaitDrawerClosed()
        // The list's fling once slipped through as a second pull, down from the closed drawer.
        assertTrue(
            "closing the drawer must not pull the shade",
            !device.wait(Until.hasObject(SHADE), GRACE_MS),
        )
    }

    @Test
    fun scrollingTheListBackUpToItsTopLeavesTheDrawerOpen() {
        tallDrawerList()
        openDrawer()
        awaitDrawerColumns(Settings.MIN_COLUMNS)
        compose.onNodeWithTag(DRAWER_LIST_TAG).performTouchInput { swipeUp() }
        compose.waitForIdle()
        // A fast swipe down: the list flies back to its top, and would overshoot into a pull.
        compose.onNodeWithTag(DRAWER_LIST_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()
        compose.onNodeWithTag(DRAWER_TAG).assertIsDisplayed()
        drawerApp(sortedDrawerLabels().first()).assertIsDisplayed() // and it is at the top
    }

    @Test
    fun aPullDownThatComesBackUpLeavesTheDrawerOpen() {
        openDrawer()
        compose.onNodeWithTag(DRAWER_LIST_TAG).performTouchInput {
            down(center)
            // Well on the way to closing, then a change of mind: back up, slowly, and let go.
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, partialPullPx() * 2 / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, -partialPullPx() / 2 / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            up()
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DRAWER_TAG).assertIsDisplayed()
        drawerApp(sortedDrawerLabels().first()).assertIsDisplayed()
    }

    @Test
    fun aPullCutShortStillLeavesTheDrawerFullyOpenOrClosed() {
        // A pull that the system cancels part way (a call comes in, another gesture takes over)
        // never reports letting go. The drawer must still end up somewhere definite.
        openDrawer()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(DRAWER_LIST_TAG).performTouchInput {
            down(center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, partialPullPx() / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            cancel()
        }
        waitUntil(TIMEOUT_MS) {
            val drawer = compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().firstOrNull()
            drawer == null || abs(drawer.boundsInRoot.top - root.top) < 1f
        }
    }

    @Test
    fun theHomeIntentClosesTheDrawerAndStaysOnTheCurrentPage() {
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeLeft() }
        waitUntil(TIMEOUT_MS) { !icon(firstHomeApp).isDisplayedOrFalse() }
        openDrawer()
        sendHomeIntent()
        awaitDrawerClosed()
        assertTrue(
            "HOME with the drawer open must not also change page",
            !icon(firstHomeApp).isDisplayedOrFalse(),
        )
    }

    @Test
    fun aPartialSwipeUpRevealsTheDrawerAndLettingGoHidesItAgain() = assertPartialPullFollowsFinger()

    @Test
    fun aPartialSwipeUpFollowsTheFingerWithTheSearchBarAboveTheDock() {
        // The pages and the bar each pull the drawer; one gesture modifier shared between the two
        // converted every page pull through the bar's position, and with the bar below the pages
        // the drawer then never caught up with the finger.
        runBlocking { graph.settings.update { it.copy(searchBarAtTop = false) } }
        waitUntil(TIMEOUT_MS) {
            val bar = compose.onNodeWithTag(SEARCH_BAR_TAG).fetchSemanticsNode().boundsInRoot
            val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
            bar.top >= pages.bottom
        }
        assertPartialPullFollowsFinger()
    }

    /** Pulls part way up from the middle of the pages: the drawer's top edge is at the finger. */
    private fun assertPartialPullFollowsFinger() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, -partialPullPx() / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
        }
        // Part way: present, not yet settled onto the screen, and its top edge at the finger.
        val finger = pages.center.y - partialPullPx()
        fun drawerTop() =
            compose
                .onAllNodesWithTag(DRAWER_TAG)
                .fetchSemanticsNodes()
                .firstOrNull()
                ?.boundsInRoot
                ?.top
        runCatching {
            waitUntil(TIMEOUT_MS) {
                drawerTop()?.let { abs(it - finger) < AT_FINGER_PX } ?: false
            }
        }
            .onFailure { throw AssertionError("drawer top ${drawerTop()} vs finger $finger", it) }
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
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().firstOrNull()?.let {
                abs(it.boundsInRoot.top - root.top) < 1f
            } ?: false
        }
        closeDrawerWithBack()
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
        waitUntil(TIMEOUT_MS) {
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
        waitUntil(TIMEOUT_MS) { placementsOf(label).any { it.x == 4 && it.y == 4 } }
    }

    @Test
    fun anAppPulledOutOfTheDrawerOntoAnOccupiedCellPushesTheOccupantAside() {
        val grid = useGrid(columns = 5, rows = 7)
        // The seed fills the top-left 4×3 of the page: dropping beside an interior icon lands on
        // an occupied cell whichever way the corner snaps, short of folding with it.
        val occupants = mapOf(0 to 1 to labelAtHomeCell(0, 1), 1 to 1 to labelAtHomeCell(1, 1))
        val label = labelOnPage(1) // not on the first page: a fresh drop, not a pick-up
        openDrawer()
        liftFromDrawer(label)
        dragOn(to = grid.homeCell(1, 1) - Offset(grid.cellWidth() * FRESH_BESIDE, 0f))
        awaitDrawerClosed()
        release()
        waitUntil(TIMEOUT_MS) { homeCellOf(label) in occupants.keys }
        val cell = homeCellOf(label)!!.let { it.first!! to it.second!! }
        val pushed = occupants.getValue(cell)
        assertTrue("$pushed still at $cell", homeCellOf(pushed) != cell)
        assertNotNull("$pushed left the page", homeCellOf(pushed))
        assertNull("folded instead of pushing aside", folderAt(cell.first, cell.second))
    }

    @Test
    fun anAppPulledOutOfTheDrawerOntoAPageThatHasItPicksUpTheOneAlreadyThere() {
        val grid = useGrid(columns = 5, rows = 7)
        val label = labelAtHomeCell(1, 0) // on the first page already
        val before = placementsOf(label).size
        openDrawer()
        liftFromDrawer(label)
        dragOn(to = grid.homeCell(4, 4))
        // The one already there is what is being carried now: its cell has emptied.
        waitUntil { icon(label).isDisplayedOrFalse().not() || homeCellOf(label) == 1 to 0 }
        release()
        waitUntil { homeCellOf(label) == 4 to 4 }
        assertEquals(before, placementsOf(label).size) // moved, not added
    }

    @Test
    fun lettingGoOffAnyTargetAfterPickingUpPutsItBackWhereItWas() {
        useGrid(columns = 5, rows = 7)
        val label = labelAtHomeCell(1, 0)
        openDrawer()
        liftFromDrawer(label)
        val bar = compose.onNodeWithTag(SEARCH_BAR_TAG).fetchSemanticsNode().boundsInRoot.center
        dragOn(to = bar) // over the search bar: no cell or slot there
        release()
        awaitDropSettled()
        assertEquals(1 to 0, homeCellOf(label))
    }

    @Test
    fun anAppPulledOutOfTheDrawerOntoATakenDockSlotJoinsItInAFolder() {
        val grid = useGrid(columns = 5, rows = 7)
        // One from a home page: an app already in the dock would be picked up and reordered
        // instead, and the one in that slot is in a folder once, so that would add nothing.
        val label = labelAtHomeCell(1, 0)
        val before = placementsOf(label).size
        openDrawer()
        liftFromDrawer(label)
        dragOn(to = grid.dockSlot(0))
        release()
        awaitDrawerClosed()
        waitUntil(TIMEOUT_MS) { dockFolderAt(0) != null }
        assertEquals(listOf(firstDockApp, label), dockFolderAt(0))
        // The page picked its own placement up as the drag crossed it, so that one went into the
        // folder: the app is in the folder now, not on a cell of its own.
        assertEquals(before - 1, placementsOf(label).size)
    }

    private fun firstDrawerLabel() = labels.first()

    private fun liftFromDrawer(label: String) = lift(drawerApp(label))

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
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().firstOrNull()?.let {
                abs(it.boundsInRoot.top - root.top) < 1f
            } ?: false
        }
    }

    @Test
    fun aSwipeUpFromTheSearchBarOpensTheDrawerToo() {
        compose.onNodeWithTag(SEARCH_BAR_TAG).performTouchInput { swipeUp() }
        waitUntil(TIMEOUT_MS) {
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun typingInTheDrawerNarrowsTheListToTheMatchingApps() {
        openDrawer()
        searchField().assertIsFocused() // the keyboard comes up with the drawer
        searchField().performTextInput(firstHomeApp)
        waitUntil(TIMEOUT_MS) { drawerItems().size == 1 }
        drawerApp(firstHomeApp).assertIsDisplayed()
    }

    @Test
    fun theAppEnterWouldLaunchIsLitWhileATextIsTyped() {
        openDrawer()
        litApps().assertCountEquals(0) // nothing typed, nothing picked
        searchField().performTextInput(firstHomeApp.take(1))
        waitUntil(TIMEOUT_MS) { litApps().fetchSemanticsNodes().size == 1 }
        // The lit tile is the first shown, the one Enter launches.
        val lit = litApps().fetchSemanticsNodes().single().boundsInRoot
        val first =
            drawerItems().minWith(compareBy({ it.boundsInRoot.top }, { it.boundsInRoot.left }))
        assertEquals(first.boundsInRoot.center, lit.center)
    }

    private fun litApps() = compose.onAllNodes(hasTestTag(DRAWER_ITEM_TAG) and isSelected())

    @Test
    fun closingTheDrawerTakesFocusOffTheSearchFieldAtOnce() {
        openDrawer()
        searchField().assertIsFocused()
        awaitKeyboard()
        device.pressBack() // the keyboard's own Back: it goes, the field keeps focus
        waitUntil(LONG_TIMEOUT_MS) { !keyboardVisible() }
        searchField().assertIsFocused()
        // Hold the clock: with it running, the first look would already be the finished close.
        compose.mainClock.autoAdvance = false
        try {
            device.pressBack() // the drawer's: focus leaves the field as the close begins
            SystemClock.sleep(PRESS_SETTLE_MS) // the key lands
            repeat(CLOSE_FRAMES) { compose.mainClock.advanceTimeByFrame() }
            compose.onNodeWithTag(DRAWER_TAG).assertExists() // still on its way out
            searchField().assertIsNotFocused()
        } finally {
            compose.mainClock.autoAdvance = true
        }
        awaitDrawerClosed()
    }

    private fun keyboardVisible() =
        ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.isVisible(WindowInsetsCompat.Type.ime()) == true

    @Test
    fun typingQuicklyKeepsEveryCharacter() {
        openDrawer()
        searchField().assertIsFocused()
        awaitKeyboard() // on a slow device it is still attaching; keystrokes before that are lost
        // Keystrokes one after another as fast as the system delivers them, not one committed
        // string: each must land on the field as the last one left it.
        device.executeShellCommand("input text $TYPED")
        waitUntil(TIMEOUT_MS) { searchText() == TYPED }
        searchField().assertTextEquals(TYPED)
    }

    @Test
    fun enterWithNothingTypedLaunchesNothing() {
        openDrawer()
        searchField().assertIsFocused()
        searchField().performImeAction()
        compose.waitForIdle()
        Thread.sleep(WRITE_GRACE_MS) // a launch would have taken the screen by now
        assertStillOnLauncher()
        compose.onNodeWithTag(DRAWER_TAG).assertIsDisplayed()
        searchField().performTextInput("   ")
        searchField().performImeAction()
        Thread.sleep(WRITE_GRACE_MS)
        assertStillOnLauncher()
        compose.onNodeWithTag(DRAWER_TAG).assertIsDisplayed()
    }

    @Test
    fun enterInTheDrawerLaunchesTheMatchAndClosesTheDrawer() {
        openDrawer()
        searchField().performTextInput(firstHomeApp)
        waitUntil(TIMEOUT_MS) { drawerItems().size == 1 }
        searchField().performImeAction()
        awaitSettingsOpen()
        device.pressBack()
        awaitDrawerClosed()
    }

    @Test
    fun reopeningTheDrawerStartsWithAnEmptySearch() {
        openDrawer()
        searchField().performTextInput(firstHomeApp)
        waitUntil(TIMEOUT_MS) { drawerItems().size == 1 }
        sendHomeIntent()
        awaitDrawerClosed()
        openDrawer()
        waitUntil(TIMEOUT_MS) { drawerItems().size > 1 }
    }

    @Test
    fun withTheKeyboardSettingOffTheFieldWaitsToBeTapped() {
        runBlocking { graph.settings.update { it.copy(drawerKeyboard = false) } }
        compose.waitForIdle()
        openDrawer()
        searchField().assertIsNotFocused()
        searchField().performClick()
        searchField().assertIsFocused()
    }

    @Test
    fun touchingALetterOfTheIndexJumpsTheListToIt() {
        tallDrawerList(index = true)
        openDrawer()
        awaitDrawerColumns(Settings.MIN_COLUMNS)
        // The letter of an app a third of the way in, and the first app under that letter: the
        // one a jump brings to the top.
        val letter = listOf(drawerLabelAThirdIn()).index { it }.single().letter
        val target = labels.first { listOf(it).index { l -> l }.single().letter == letter }
        compose.onNodeWithTag(DRAWER_INDEX_TAG).assertIsDisplayed()
        compose.onNode(hasContentDescription("Jump to $letter")).performTouchInput { click() }
        waitUntil(TIMEOUT_MS) { drawerApp(target).isDisplayedOrFalse() }
        // At the top of the list, not merely somewhere on the screen; unless the list ran out
        // first, on a device with few apps, in which case its end is showing.
        val list = compose.onNodeWithTag(DRAWER_LIST_TAG).fetchSemanticsNode().boundsInRoot
        val item = drawerApp(target).fetchSemanticsNode().boundsInRoot
        val atTop = item.top - list.top < list.height * JUMP_TOLERANCE
        assertTrue(
            "$target at ${item.top} should sit at the top of the list (${list.top})",
            atTop || drawerApp(lastDrawerLabel()).isDisplayedOrFalse(),
        )
    }

    @Test
    fun theIndexStaysOutOfTheWayWhileSearching() {
        runBlocking { graph.settings.update { it.copy(drawerIndex = true) } }
        compose.waitForIdle()
        openDrawer()
        compose.onNodeWithTag(DRAWER_INDEX_TAG).assertIsDisplayed()
        searchField().performTextInput(firstHomeApp)
        waitUntil(TIMEOUT_MS) { drawerItems().size == 1 }
        compose.onAllNodesWithTag(DRAWER_INDEX_TAG).assertCountEquals(0)
    }

    @Test
    fun theIndexIsOffUnlessAskedFor() {
        openDrawer()
        compose.onAllNodesWithTag(DRAWER_INDEX_TAG).assertCountEquals(0)
    }

    private fun drawerLabelAThirdIn() = labels[labels.size / 3]

    private fun lastDrawerLabel() = labels.last()

    private fun sortedDrawerLabels() = labels

    @Test
    fun theDrawerFollowsTheHomeColumnsUntilGivenItsOwn() {
        openDrawer()
        assertEquals(settings.columns, drawerRowLength())
        runBlocking { graph.settings.update { it.copy(drawerColumns = DRAWER_COLUMNS) } }
        waitUntil(TIMEOUT_MS) { drawerRowLength() == DRAWER_COLUMNS }
        // Six across a 4-column screen: the icons shrink to fit rather than overlap.
        val first = drawerItems().first().boundsInRoot
        val second = drawerItems()[1].boundsInRoot
        assertTrue("items overlap: $first then $second", second.left >= first.right - 1f)
    }

    /** How many apps share the first row. */
    private fun drawerRowLength(): Int {
        val items = drawerItems()
        val top = items.first().boundsInRoot.top
        return items.count { abs(it.boundsInRoot.top - top) < 1f }
    }

    private fun searchField() = compose.onNodeWithTag(DRAWER_SEARCH_TAG)

    private fun searchText() =
        searchField().fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun drawerItems() = compose.onAllNodesWithTag(DRAWER_ITEM_TAG).fetchSemanticsNodes()

    @Test
    fun aSwipeDownShowsTheShadeComingAndLetsGoIfItDoesNot() {
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, partialPullPx() / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
        }
        compose.onNodeWithTag(SHADE_HINT_TAG, useUnmergedTree = true).assertExists()
        // And the drawer has not so much as stirred.
        compose.onAllNodesWithTag(DRAWER_TAG).assertCountEquals(0)
        compose.onRoot().performTouchInput { up() }
        awaitGone(SHADE_HINT_TAG)
        assertTrue("too short a pull for the shade", !device.hasObject(SHADE))
    }

    @Test
    fun easingBackDownALittleAfterPullingUpCancelsOpening() {
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, -root.height * OPENING_PULL / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            // Back down a little, slowly: not a flick, but a change of mind.
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, EASE_BACK_PX / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            up()
        }
        awaitDrawerClosed()
    }

    @Test
    fun aSwipeUpThatComesBackDownDoesNothingAtAll() {
        // Enough up to show the drawer, then a change of mind, flicked down past the start.
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, -root.height * OPENING_PULL / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            repeat(3) {
                moveBy(Offset(0f, root.height * OPENING_PULL / 2))
                advanceEventTime(QUICK_STEP_MS)
            }
            up()
        }
        awaitDrawerClosed()
        assertTrue(
            "a reversed swipe must not open the shade",
            !device.wait(Until.hasObject(SHADE), GRACE_MS),
        )
        compose.onAllNodesWithTag(SHADE_HINT_TAG, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun swipingDownOnThePagesPullsDownTheNotificationShade() {
        pullDown(compose.onRoot().fetchSemanticsNode().boundsInRoot.height * OPENING_PULL)
        assertTrue(
            "the notification shade should have come down",
            device.wait(Until.hasObject(SHADE), TIMEOUT_MS),
        )
    }

    @Test
    fun aShortSwipeDownLeavesTheHomeScreenAsItWas() {
        pullDown(partialPullPx())
        assertFalse("no shade for a pull this short", device.wait(Until.hasObject(SHADE), GRACE_MS))
        assertTrue(
            "nor any drawer",
            compose.onAllNodesWithTag(DRAWER_TAG).fetchSemanticsNodes().isEmpty(),
        )
    }

    /** The shade outlives a test that pulled it down; the next test wants the home screen. */
    @After
    fun collapseShade() {
        device.executeShellCommand("cmd statusbar collapse")
        device.wait(Until.gone(SHADE), TIMEOUT_MS)
    }

    private fun pullDown(px: Float) {
        val pages = compose.onNodeWithTag(WORKSPACE_TAG).fetchSemanticsNode().boundsInRoot
        compose.onRoot().performTouchInput {
            down(pages.center)
            repeat(PULL_STEPS) {
                moveBy(Offset(0f, px / PULL_STEPS))
                advanceEventTime(PULL_STEP_MS)
            }
            up()
        }
    }

    /** With the keyboard up the first back only hides that, as in any app; then back closes. */
    private fun closeDrawerWithBack() {
        device.pressBack()
        if (!drawerGoneWithin(KEYBOARD_GRACE_MS)) device.pressBack()
        awaitDrawerClosed()
    }

    private fun drawerGoneWithin(ms: Long) = runCatching { awaitDrawerClosed(ms) }.isSuccess

    /**
     * The fewest columns the settings allow, on a home grid of the same few columns and few rows:
     * the icons are then as big as they get, and the drawer's list is far taller than the screen,
     * so there is something to scroll and a jump has somewhere to go.
     */
    private fun tallDrawerList(index: Boolean = false) {
        runBlocking {
            graph.settings.update {
                it.copy(
                    columns = Settings.MIN_COLUMNS,
                    rows = Settings.MIN_ROWS,
                    drawerColumns = Settings.MIN_COLUMNS,
                    drawerIndex = index,
                )
            }
        }
    }

    /**
     * A setting written to the store reaches the screen by way of a flow the test clock knows
     * nothing of; the drawer can open before it lands. [columns] shows as that many distinct left
     * edges among the apps.
     */
    private fun awaitDrawerColumns(columns: Int) {
        waitUntil(TIMEOUT_MS) {
            val items = drawerItems()
            items.size > columns && items.map { it.boundsInRoot.left }.toSet().size == columns
        }
    }

    private companion object {
        /** Frames into the drawer's close at which it is still on its way out. */
        const val CLOSE_FRAMES = 3
        /** Outside the fold zone of the icon the finger is beside. */
        const val FRESH_BESIDE = 0.45f
        const val PULL_STEPS = 10
        const val TYPED = "quickbrownfox"
        const val QUICK_SWIPE = 0.08f
        const val QUICK_STEP_MS = 16L
        const val PULL_STEP_MS = 40L // slow enough not to count as a flick
        const val OPENING_PULL = 0.35f // well past a third of the pull distance
        const val MODEST_PULL = 0.05f // between High's 1% and Low's 8%
        const val JUMP_TOLERANCE = 0.1f // the list's top padding, and then some
        const val DRAWER_COLUMNS = 6 // more than the fixture's 4 home columns
        const val KEYBOARD_GRACE_MS = 1_000L // a closing drawer is long gone by then
        const val GRACE_MS = 1_000L // long enough for a shade that was going to come down
        const val AT_FINGER_PX = 24f // the drawer's top edge is under the finger, give or take
        const val EASE_BACK_PX = 40f // slightly: over the 8 dp that reads as a change of mind
        val SHADE: BySelector = By.res("com.android.systemui", "notification_stack_scroller")
    }
}
