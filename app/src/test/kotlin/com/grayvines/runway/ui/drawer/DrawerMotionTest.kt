package com.grayvines.runway.ui.drawer

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DrawerMotionTest {
    @Test
    fun `a released drawer opens past a third and falls back before it`() {
        assertFalse(shouldOpen(revealed = 0.1f, upwardsPerSecond = 0f))
        assertFalse(shouldOpen(revealed = 0.29f, upwardsPerSecond = 0f))
        assertTrue(shouldOpen(revealed = 0.3f, upwardsPerSecond = 0f))
        assertTrue(shouldOpen(revealed = 0.9f, upwardsPerSecond = 0f))
    }

    @Test
    fun `a flick decides regardless of how much is showing`() {
        assertTrue(shouldOpen(revealed = 0.05f, upwardsPerSecond = 2f))
        assertFalse(shouldOpen(revealed = 0.95f, upwardsPerSecond = -2f))
        assertFalse(shouldOpen(revealed = 0.1f, upwardsPerSecond = 1f)) // too slow to count
    }
}
