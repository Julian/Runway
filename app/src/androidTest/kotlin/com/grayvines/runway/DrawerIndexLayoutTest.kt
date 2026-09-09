package com.grayvines.runway

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.runway.ui.drawer.DRAWER_INDEX_TAG
import com.grayvines.runway.ui.drawer.IndexEntry
import com.grayvines.runway.ui.drawer.IndexedGrid
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The alphabet index's place over the grid, with something (the keyboard) covering the bottom. */
class DrawerIndexLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theIndexIsCentredInTheRoomAboveTheKeyboard() {
        val (grid, index) = laidOut(above = HEIGHT / 2)
        val room = Rect(grid.left, grid.top, grid.right, grid.bottom - grid.height / 2)
        assertTrue(
            "index $index reaches under the keyboard (from ${room.bottom})",
            index.bottom <= room.bottom,
        )
        assertTrue(
            "index $index is not centred in $room",
            abs(index.center.y - room.center.y) <= 1f,
        )
    }

    @Test
    fun withNoKeyboardTheIndexIsCentredOnTheGrid() {
        val (grid, index) = laidOut(above = 0.dp)
        assertTrue(
            "index $index is not centred on $grid",
            abs(index.center.y - grid.center.y) <= 1f,
        )
    }

    private fun laidOut(above: Dp): Pair<Rect, Rect> {
        compose.setContent {
            Box(Modifier.size(WIDTH, HEIGHT)) {
                IndexedGrid(
                    entries = ('A'..'E').mapIndexed { i, letter -> IndexEntry(letter, i) },
                    state = rememberLazyGridState(),
                    indexed = true,
                    above = above,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
        val grid = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val index = compose.onNodeWithTag(DRAWER_INDEX_TAG).fetchSemanticsNode().boundsInRoot
        return grid to index
    }

    private companion object {
        val WIDTH = 300.dp
        val HEIGHT = 600.dp
    }
}
