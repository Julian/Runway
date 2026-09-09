package com.grayvines.runway

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.home.DOCK_TAG
import com.grayvines.runway.ui.home.PAGE_DOTS_TAG
import com.grayvines.runway.ui.home.WORKSPACE_TAG
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Page dots for the home pages and the dock: there while swiping, gone otherwise. */
@RunWith(AndroidJUnit4::class)
class PageIndicatorTest : LauncherFixture() {
    @Test
    fun dotsShowWhileTheHomePagesMoveAndGoAwayAfter() {
        val pages = pageCount(Container.HOME)
        assertTrue("the fixture's layout has one page; the dots need two", pages > 1)
        compose.onNodeWithTag(WORKSPACE_TAG).performTouchInput { swipeLeft() }
        waitUntil { dots().isNotEmpty() }
        compose.onNodeWithTag(PAGE_DOTS_TAG).onChildren().assertCountEquals(pages)
        waitUntil { dots().isEmpty() } // settled, lingered, gone
    }

    @Test
    fun theDockShowsItsOwnDots() {
        runBlocking { graph.workspace.addPage(Container.DOCK, 1) }
        waitUntil { pageCount(Container.DOCK) == 2 }
        compose.onNodeWithTag(DOCK_TAG).performTouchInput { swipeLeft() }
        waitUntil { dots().isNotEmpty() }
        compose.onNodeWithTag(PAGE_DOTS_TAG).onChildren().assertCountEquals(2)
        waitUntil { dots().isEmpty() }
    }

    @Test
    fun aSinglePageShowsNoDots() {
        compose.onNodeWithTag(DOCK_TAG).performTouchInput { swipeLeft() } // the dock has one page
        compose.mainClock.advanceTimeBy(LINGER_CHECK_MS)
        assertTrue(dots().isEmpty())
    }

    private fun dots() = compose.onAllNodesWithTag(PAGE_DOTS_TAG).fetchSemanticsNodes()

    private fun pageCount(container: Container) = runBlocking {
        graph.workspace.observe(container).first().pages.size
    }

    private companion object {
        const val LINGER_CHECK_MS = 500L
    }
}
