package com.grayvines.runway.ui.drawer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PullToCloseTest {
    private val pulls = mutableListOf<Float>()
    private var ended: Float? = null
    private var atTop = true
    private var revealed = 1f

    private val connection =
        PullToClose(
            revealed = { revealed },
            atTop = { atTop },
            onPull = { pulls += it },
            onPullEnd = { ended = it },
        )

    @Test
    fun `a pull from the top takes the leftover and the fling, so nothing stretches`() = runTest {
        connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
        val taken =
            connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
        assertEquals(Offset(0f, 30f), taken)
        assertEquals(listOf(30f), pulls)

        val flung = connection.onPostFling(Velocity.Zero, Velocity(0f, 500f))
        assertEquals(Velocity(0f, 500f), flung)
        assertEquals(500f, ended)
    }

    @Test
    fun `pushing back up while part-way down pulls the drawer back before scrolling the list`() =
        runTest {
            connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
            connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
            revealed = 0.7f

            val taken = connection.onPreScroll(Offset(0f, -15f), NestedScrollSource.UserInput)
            assertEquals(Offset(0f, -15f), taken)
            assertEquals(listOf(30f, -15f), pulls)

            // Once all the way up again, further upward movement goes to the list.
            revealed = 1f
            val atRest = connection.onPreScroll(Offset(0f, -10f), NestedScrollSource.UserInput)
            assertEquals(Offset.Zero, atRest)
            assertEquals(listOf(30f, -15f), pulls)
        }

    @Test
    fun `what a fling leaves over after the finger lifts is taken, but is not a pull`() = runTest {
        connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
        val taken =
            connection.onPostScroll(Offset.Zero, Offset(0f, 200f), NestedScrollSource.SideEffect)
        assertEquals(Offset(0f, 200f), taken)
        assertEquals(listOf(30f), pulls)
    }

    @Test
    fun `a swipe that began away from the top is the list's, leftover and fling alike`() = runTest {
        atTop = false
        connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
        val taken =
            connection.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
        assertEquals(Offset.Zero, taken)
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, Velocity(0f, 500f)))
        assertEquals(emptyList<Float>(), pulls)
        assertEquals(null, ended)
    }

    @Test
    fun `after a fling finishes, the next swipe decides anew from where the list is then`() =
        runTest {
            // First gesture starts away from the top: does not pull.
            atTop = false
            connection.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
            connection.onPostFling(Velocity.Zero, Velocity(0f, 500f))
            assertEquals(emptyList<Float>(), pulls)

            // Second gesture starts at the top: now it pulls.
            atTop = true
            connection.onPreScroll(Offset(0f, 25f), NestedScrollSource.UserInput)
            val taken =
                connection.onPostScroll(Offset.Zero, Offset(0f, 25f), NestedScrollSource.UserInput)
            assertEquals(Offset(0f, 25f), taken)
            assertEquals(listOf(25f), pulls)
        }
}
