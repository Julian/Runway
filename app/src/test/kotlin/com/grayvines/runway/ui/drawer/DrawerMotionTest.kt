package com.grayvines.runway.ui.drawer

import androidx.compose.runtime.MonotonicFrameClock
import com.grayvines.runway.data.settings.DrawerSwipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrawerMotionTest {
    /** Velocities in px/s against a 400 px/s flick; 30 px/s back is a change of mind. */
    private fun opens(revealed: Float, upwards: Float, openAt: Float = 0.3f, flick: Float = 400f) =
        shouldOpen(revealed, upwards, openAt, flick, againstPxPerSecond = 30f)

    @Test
    fun `moving back at all when letting go falls back, holding still does not`() {
        assertFalse(opens(revealed = 0.9f, upwards = -50f)) // slowly back down: a change of mind
        assertTrue(opens(revealed = 0.9f, upwards = -10f)) // the jitter of a still finger
    }

    @Test
    fun `a released drawer opens past the threshold and falls back before it`() {
        assertFalse(opens(revealed = 0.1f, upwards = 0f))
        assertFalse(opens(revealed = 0.29f, upwards = 0f))
        assertTrue(opens(revealed = 0.3f, upwards = 0f))
        assertTrue(opens(revealed = 0.9f, upwards = 0f))
        assertTrue(opens(revealed = 0.1f, upwards = 0f, openAt = 0.1f)) // a sensitive setting
        assertTrue(
            opens(revealed = 0.02f, upwards = 0f, openAt = 0.0167f)
        ) // High: barely past slop
    }

    @Test
    fun `a flick decides regardless of how much is showing`() {
        assertTrue(opens(revealed = 0.05f, upwards = 600f))
        assertFalse(opens(revealed = 0.95f, upwards = -600f))
        assertFalse(opens(revealed = 0.1f, upwards = 300f)) // too slow to count
        assertTrue(
            opens(revealed = 0.1f, upwards = 300f, flick = 250f)
        ) // unless the setting says so
    }

    /** What letting go asked for. */
    private enum class Asked {
        NOTHING,
        OPEN,
        CLOSE,
        SHADE,
    }

    /** A 1000 px tall screen at density 1 with the default sensitivity: 20 px opens either way. */
    private fun runWithMotion(
        block: suspend TestScope.(DrawerMotion, release: (Float, Boolean) -> Asked) -> Unit
    ) = runTest {
        val motion = DrawerMotion(CoroutineScope(coroutineContext + ImmediateFrames))
        motion.laidOut(heightPx = 1000f, density = 1f, swipe = DrawerSwipe.MEDIUM)
        block(motion) { velocity, open ->
            var asked = Asked.NOTHING
            motion.release(
                velocity,
                open,
                onOpen = { asked = Asked.OPEN },
                onClose = { asked = Asked.CLOSE },
                onOpenShade = { asked = Asked.SHADE },
            )
            asked
        }
    }

    @Test
    fun `a pull down from a closed drawer asks for the notification shade`() =
        runWithMotion { motion, release ->
            repeat(5) { motion.dragBy(10f) }
            assertEquals(Asked.SHADE, release(0f, false))
            assertEquals(0f, motion.revealed.value)
        }

    @Test
    fun `the drawer catches up with the finger, then moves with it one to one`() =
        runWithMotion { motion, release ->
            motion.startPull(fingerY = 800f) // 200 px up a 1000 px screen
            motion.dragBy(-10f) // the first move upward commits the swipe: off it sets
            advanceUntilIdle() // the catch-up runs its course
            assertEquals(0.21f, motion.shown.value, 0.01f) // its top edge is at the finger
            motion.dragBy(-100f)
            assertEquals(0.31f, motion.shown.value, 0.01f)
            motion.dragBy(5f) // a wobble, not a change of mind
            assertEquals(0.305f, motion.shown.value, 0.01f)
            // Let go: 305 px is past the threshold, so it asks to open, and until the opening
            // animation takes over it stays drawn where the finger left it.
            assertEquals(Asked.OPEN, release(0f, false))
            advanceUntilIdle()
            assertEquals(0.305f, motion.shown.value, 0.01f)
        }

    @Test
    fun `a pull down never lifts the drawer, however slow`() = runWithMotion { motion, release ->
        motion.startPull(fingerY = 800f)
        motion.dragBy(3f) // a slow start, not yet committed either way
        advanceUntilIdle()
        assertEquals(0f, motion.shown.value, 0.001f)
        repeat(5) { motion.dragBy(10f) } // committed downward
        advanceUntilIdle()
        assertEquals(0f, motion.shown.value, 0.001f)
        assertEquals(Asked.SHADE, release(0f, false))
    }

    @Test
    fun `a pull that begins inside the open drawer moves it from where it is`() =
        runWithMotion { motion, release ->
            motion.revealed.snapTo(1f)
            motion.dragBy(100f) // down, with no finger position given
            assertEquals(0.9f, motion.shown.value, 0.01f)
            release(0f, true)
        }

    @Test
    fun `a short pull down asks for nothing`() = runWithMotion { motion, release ->
        motion.dragBy(10f)
        assertEquals(Asked.NOTHING, release(0f, false))
    }

    @Test
    fun `a pull down gives way by how close it is to the shade, and lets go on release`() =
        runWithMotion { motion, release ->
            motion.dragBy(10f) // half of the 20 px that would open the shade
            advanceUntilIdle() // the finger's moves reach the animatables on their own frames
            assertEquals(0.5f, motion.given.value, 0.01f)
            motion.dragBy(30f) // past it: all the way, no further
            advanceUntilIdle()
            assertEquals(1f, motion.given.value, 0.01f)
            release(0f, false)
            advanceUntilIdle()
            assertEquals(0f, motion.given.value, 0.01f)
        }

    @Test
    fun `a quick flick down asks for the shade however short`() = runWithMotion { motion, release ->
        motion.dragBy(5f)
        assertEquals(Asked.SHADE, release(2000f, false))
    }

    @Test
    fun `a pull is in progress from its first move until it is released`() =
        runWithMotion { motion, release ->
            assertFalse(motion.pulling)
            motion.dragBy(-10f)
            assertTrue(motion.pulling)
            release(0f, false)
            assertFalse(motion.pulling)
        }

    @Test
    fun `a pull down that comes back up asks for nothing`() = runWithMotion { motion, release ->
        repeat(5) { motion.dragBy(10f) }
        repeat(5) { motion.dragBy(-10f) }
        assertEquals(Asked.NOTHING, release(0f, false))
    }

    @Test
    fun `a swipe commits to its first direction, so up then back down never asks for the shade`() =
        runWithMotion { motion, release ->
            repeat(10) { motion.dragBy(-10f) } // up: the drawer starts to show
            repeat(20) { motion.dragBy(10f) } // a change of mind, well past where it started
            advanceUntilIdle()
            assertEquals(0f, motion.given.value, 0.01f) // never on its way to the shade
            assertEquals(Asked.NOTHING, release(2000f, false)) // even flicked down
        }

    @Test
    fun `coming back a little from the farthest point cancels, a smaller wobble does not`() =
        runWithMotion { motion, release ->
            repeat(30) { motion.dragBy(-10f) } // 300 px up: well past the threshold
            repeat(3) { motion.dragBy(10f) } // 30 px back down: a change of mind
            assertEquals(Asked.NOTHING, release(0f, false))

            motion.startPull(null)
            repeat(30) { motion.dragBy(-10f) }
            motion.dragBy(5f) // 5 px: a wobble, within the 8 dp that reads as a change of mind
            assertEquals(Asked.OPEN, release(0f, false))
        }

    @Test
    fun `a swipe down then back up never asks for the drawer`() = runWithMotion { motion, release ->
        repeat(10) { motion.dragBy(10f) }
        repeat(20) { motion.dragBy(-10f) }
        advanceUntilIdle()
        assertEquals(0f, motion.revealed.value, 0.01f)
        assertEquals(Asked.NOTHING, release(-2000f, false))
    }

    @Test
    fun `a pull up still asks for the drawer, never the shade`() =
        runWithMotion { motion, release ->
            repeat(5) { motion.dragBy(-10f) }
            assertEquals(Asked.OPEN, release(0f, false))
        }

    @Test
    fun `pulling down inside an open drawer closes it rather than opening the shade`() =
        runWithMotion { motion, release ->
            motion.revealed.snapTo(1f)
            repeat(70) { motion.dragBy(10f) } // past the bottom of a 600 px pull
            assertEquals(Asked.CLOSE, release(0f, true))
        }

    @Test
    fun `a pull down inside an open drawer closes past the threshold, and stays short of it`() =
        runWithMotion { motion, release ->
            motion.revealed.snapTo(1f)
            repeat(10) { motion.dragBy(10f) } // 100 px: past the 20 px that decides either way
            assertEquals(Asked.CLOSE, release(0f, true))

            motion.revealed.snapTo(1f)
            motion.startPull(null)
            motion.dragBy(10f) // 10 px: not far enough to mean it
            assertEquals(Asked.NOTHING, release(0f, true))
        }

    @Test
    fun `a pull down inside an open drawer that comes back up, or flicks up, leaves it open`() =
        runWithMotion { motion, release ->
            motion.revealed.snapTo(1f)
            repeat(30) { motion.dragBy(10f) } // 300 px down: well past the threshold
            repeat(3) { motion.dragBy(-10f) } // 30 px back up: a change of mind
            assertEquals(Asked.NOTHING, release(0f, true))

            motion.revealed.snapTo(1f)
            motion.startPull(null)
            repeat(30) { motion.dragBy(10f) }
            assertEquals(Asked.NOTHING, release(-2000f, true)) // flicked back up
            motion.startPull(null)
            repeat(30) { motion.dragBy(10f) }
            assertEquals(Asked.CLOSE, release(2000f, true)) // flicked on down
        }

    @Test
    fun `letting go with no pull in progress decides nothing for an open drawer`() =
        runWithMotion { motion, release ->
            motion.revealed.snapTo(1f)
            // A list fling reporting itself after a scroll back to the top: nothing was pulled.
            assertEquals(Asked.NOTHING, release(500f, true))
        }
}

/** Animations finish at once: these tests care about decisions, not frames. */
private object ImmediateFrames : MonotonicFrameClock {
    private const val FRAME_NANOS = 16_000_000L
    private var now = 0L

    override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
        now += FRAME_NANOS
        return onFrame(now)
    }
}
