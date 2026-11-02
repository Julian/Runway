package com.grayvines.runway.ui.home

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WallpaperParallaxTest {
    @Test
    fun `divides the wallpaper into equal steps across multiple pages when enabled`() {
        assertEquals(0.5f, wallpaperStep(pageCount = 3, enabled = true))
        assertEquals(0.25f, wallpaperStep(pageCount = 5, enabled = true))
        assertEquals(1f, wallpaperStep(pageCount = 1, enabled = true))
        assertEquals(1f, wallpaperStep(pageCount = 0, enabled = true))
        assertEquals(1f, wallpaperStep(pageCount = 3, enabled = false))
    }

    @Test
    fun `slides from 0 to 1 across pages and clamps overscroll when enabled`() {
        assertEquals(
            0f,
            wallpaperOffset(page = 0, offsetFraction = 0f, pageCount = 3, enabled = true),
        )
        assertEquals(
            0.25f,
            wallpaperOffset(page = 0, offsetFraction = 0.5f, pageCount = 3, enabled = true),
        )
        assertEquals(
            0.25f,
            wallpaperOffset(page = 1, offsetFraction = -0.5f, pageCount = 3, enabled = true),
        )
        assertEquals(
            0.5f,
            wallpaperOffset(page = 1, offsetFraction = 0f, pageCount = 3, enabled = true),
        )
        assertEquals(
            1f,
            wallpaperOffset(page = 2, offsetFraction = 0f, pageCount = 3, enabled = true),
        )
        assertEquals(
            0f,
            wallpaperOffset(page = 0, offsetFraction = -0.2f, pageCount = 3, enabled = true),
        )
        assertEquals(
            1f,
            wallpaperOffset(page = 2, offsetFraction = 0.2f, pageCount = 3, enabled = true),
        )
    }

    @Test
    fun `centres the wallpaper when parallax is off or there is only one page`() {
        assertEquals(
            0.5f,
            wallpaperOffset(page = 0, offsetFraction = 0f, pageCount = 1, enabled = true),
        )
        assertEquals(
            0.5f,
            wallpaperOffset(page = 0, offsetFraction = 0f, pageCount = 3, enabled = false),
        )
        assertEquals(
            0.5f,
            wallpaperOffset(page = 2, offsetFraction = 0f, pageCount = 3, enabled = false),
        )
    }
}
