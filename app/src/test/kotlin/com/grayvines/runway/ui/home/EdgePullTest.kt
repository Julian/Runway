package com.grayvines.runway.ui.home

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EdgePullTest {
    /** 20 px past slop runs it, or a 400 px/s flick; 8 px or 30 px/s back is a change of mind. */
    private fun pulled(vararg moves: Float) =
        EdgePull(
                openAtPx = 20f,
                flickPxPerSecond = 400f,
                reversalPx = 8f,
                againstPxPerSecond = 30f,
            )
            .apply { moves.forEach(::moveBy) }

    @Test
    fun `letting go past the distance runs the action, short of it does not`() {
        assertFalse(pulled(19f).runs(0f))
        assertTrue(pulled(20f).runs(0f))
        assertTrue(pulled(5f, 10f, 10f).runs(0f))
    }

    @Test
    fun `a flick runs it however short the swipe, a slower one does not`() {
        assertTrue(pulled(2f).runs(500f))
        assertFalse(pulled(2f).runs(300f))
    }

    @Test
    fun `coming back from the farthest point runs nothing, however far it got`() {
        assertFalse(pulled(100f, -9f).runs(0f))
        assertFalse(pulled(100f, -9f).runs(500f)) // a flick outwards does not undo a change of mind
        assertTrue(pulled(100f, -7f).runs(0f)) // the wobble of a finger letting go
    }

    @Test
    fun `moving back as it lets go runs nothing, holding still does`() {
        assertFalse(pulled(100f).runs(-50f))
        assertTrue(pulled(100f).runs(-10f))
    }
}
