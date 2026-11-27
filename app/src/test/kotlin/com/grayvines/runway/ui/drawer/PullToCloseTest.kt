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

    @Test
    fun `scrolling down from the top and reversing back past top does not pull the drawer`() =
        runTest {
            // Starts at top, but swipes up to scroll the list down.
            connection.onPreScroll(Offset(0f, -40f), NestedScrollSource.UserInput)
            connection.onPostScroll(
                consumed = Offset(0f, -40f),
                available = Offset.Zero,
                source = NestedScrollSource.UserInput,
            )
            // Reverses back down past the top in the same gesture.
            val taken =
                connection.onPostScroll(
                    consumed = Offset(0f, 40f),
                    available = Offset(0f, 30f),
                    source = NestedScrollSource.UserInput,
                )
            assertEquals(Offset.Zero, taken)
            assertEquals(Velocity.Zero, connection.onPostFling(Velocity.Zero, Velocity(0f, 300f)))
            assertEquals(emptyList<Float>(), pulls)
            assertEquals(null, ended)
        }

    @Test
    fun `a normal upward scroll from the top does not fire onPullEnd`() = runTest {
        connection.onPreScroll(Offset(0f, -50f), NestedScrollSource.UserInput)
        connection.onPostScroll(
            consumed = Offset(0f, -50f),
            available = Offset.Zero,
            source = NestedScrollSource.UserInput,
        )
        assertEquals(Velocity.Zero, connection.onPostFling(Velocity(0f, -800f), Velocity.Zero))
        assertEquals(emptyList<Float>(), pulls)
        assertEquals(null, ended)
    }

    @Test
    fun `horizontal movement and velocity are left unconsumed during a pull`() = runTest {
        connection.onPreScroll(Offset(10f, 30f), NestedScrollSource.UserInput)
        connection.onPostScroll(Offset.Zero, Offset(10f, 30f), NestedScrollSource.UserInput)
        revealed = 0.7f

        val pre = connection.onPreScroll(Offset(12f, -15f), NestedScrollSource.UserInput)
        assertEquals(Offset(0f, -15f), pre)
        assertEquals(
            Velocity(0f, 400f),
            connection.onPostFling(Velocity.Zero, Velocity(120f, 400f)),
        )
    }

    @Test
    fun `onPreFling consumes an upward cancel flick mid-pull without double-firing in onPostFling`() =
        runTest {
            var endCalls = 0
            val tracked =
                PullToClose(
                    revealed = { revealed },
                    atTop = { atTop },
                    onPull = { pulls += it },
                    onPullEnd = {
                        endCalls++
                        ended = it
                    },
                )
            tracked.onPreScroll(Offset(0f, 30f), NestedScrollSource.UserInput)
            tracked.onPostScroll(Offset.Zero, Offset(0f, 30f), NestedScrollSource.UserInput)
            revealed = 0.7f

            val pre = tracked.onPreFling(Velocity(50f, -1800f))
            assertEquals(Velocity(0f, -1800f), pre)
            assertEquals(-1800f, ended)
            assertEquals(1, endCalls)

            // Subsequent onPostFling does not call onPullEnd a second time.
            assertEquals(Velocity.Zero, tracked.onPostFling(Velocity(0f, -1800f), Velocity.Zero))
            assertEquals(1, endCalls)
        }
}
