package com.grayvines.runway.ui.menu

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.grayvines.runway.ui.drag.Bounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MenuOffsetTest {
    private val menu = IntSize(200, 300)
    private val gap = 6

    @Test
    fun `below the anchor when it fits, centred on it`() {
        val anchor = Bounds(left = 100f, top = 100f, right = 200f, bottom = 200f)
        assertEquals(
            IntOffset(50, 206),
            menuOffset(anchor, menu, room = IntSize(1000, 2000), gap = gap),
        )
    }

    @Test
    fun `above the anchor when the keyboard leaves no room below`() {
        // A room 1000 tall with the anchor at its foot: the same anchor would go below on a
        // taller room, so the keyboard is what sends it above.
        val anchor = Bounds(left = 100f, top = 800f, right = 200f, bottom = 900f)
        assertEquals(IntOffset(50, 494), menuOffset(anchor, menu, IntSize(1000, 1000), gap))
        assertEquals(IntOffset(50, 906), menuOffset(anchor, menu, IntSize(1000, 2000), gap))
    }

    @Test
    fun `never past the room's edges, even when it fits nowhere`() {
        val anchor = Bounds(left = 950f, top = 100f, right = 1050f, bottom = 200f)
        val cramped = menuOffset(anchor, menu, room = IntSize(1000, 250), gap = gap)
        assertEquals(IntOffset(800, 0), cramped)
    }
}
