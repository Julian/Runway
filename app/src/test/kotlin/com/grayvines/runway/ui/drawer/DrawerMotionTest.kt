package com.grayvines.runway.ui.drawer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrawerMotionTest {
    private fun opens(revealed: Float, upwards: Float, openAt: Float = 0.3f, flick: Float = 1.5f) =
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
        assertTrue(opens(revealed = 0.05f, upwards = 2f))
        assertFalse(opens(revealed = 0.95f, upwards = -2f))
        assertFalse(opens(revealed = 0.1f, upwards = 1f)) // too slow to count
        assertTrue(opens(revealed = 0.1f, upwards = 1f, flick = 0.8f)) // unless the setting says so
    }
}
