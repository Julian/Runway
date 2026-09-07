package com.grayvines.runway.ui.drawer

import androidx.compose.runtime.MonotonicFrameClock
import com.grayvines.runway.data.settings.DrawerSwipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrawerMotionTest {
    /** Velocities in px/s against a 400 px/s flick. */
    private fun opens(revealed: Float, upwards: Float, openAt: Float = 0.3f, flick: Float = 400f) =
        shouldOpen(revealed, upwards, openAt, flick)

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
        block: suspend (DrawerMotion, release: (Float, Boolean) -> Asked) -> Unit
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
    fun `a short pull down asks for nothing`() = runWithMotion { motion, release ->
        motion.dragBy(10f)
        assertEquals(Asked.NOTHING, release(0f, false))
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
