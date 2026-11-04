package com.grayvines.runway.system.apps

import kotlin.math.hypot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IconRendererTest {
    @Test
    fun `extractIconBackgroundColor returns white when all pixels are transparent`() {
        val transparent = intArrayOf(0x00FF0000, 0x7F00FF00, 0x000000FF)
        assertEquals(0xFFFFFFFF.toInt(), IconRenderer.extractIconBackgroundColor(transparent))
    }

    @Test
    fun `extractIconBackgroundColor blends 20 percent of opaque average onto white`() {
        // Opaque pure red (255, 0, 0) + transparent green (ignored):
        // R = 255 * 0.8 + 255 * 0.2 = 255 (0xFF)
        // G = 255 * 0.8 + 0 * 0.2 = 204 (0xCC)
        // B = 255 * 0.8 + 0 * 0.2 = 204 (0xCC)
        val pixels = intArrayOf(0xFFFF0000.toInt(), 0x0000FF00)
        assertEquals(0xFFFFCCCC.toInt(), IconRenderer.extractIconBackgroundColor(pixels))
    }

    @Test
    fun `squirclePoints stays within bounds, touches cardinal midpoints, and bulges past circle`() {
        val size = 100f
        val r = size / 2f
        val points = IconRenderer.squirclePoints(size, steps = 64)
        assertEquals(64, points.size)

        for ((x, y) in points) {
            assertTrue(x in 0f..size, "x out of bounds: $x")
            assertTrue(y in 0f..size, "y out of bounds: $y")
        }

        // Step 0 is (size, r), step 16 is (r, size), step 32 is (0, r), step 48 is (r, 0).
        assertEquals(size, points[0].first, 1e-3f)
        assertEquals(r, points[0].second, 1e-3f)
        assertEquals(r, points[16].first, 1e-3f)
        assertEquals(size, points[16].second, 1e-3f)
        assertEquals(0f, points[32].first, 1e-3f)
        assertEquals(r, points[32].second, 1e-3f)
        assertEquals(r, points[48].first, 1e-3f)
        assertEquals(0f, points[48].second, 1e-3f)

        // At 45 degrees (step 8), a squircle reaches further toward the corner than a circle of
        // radius r.
        val diagonalDist = hypot(points[8].first - r, points[8].second - r)
        assertTrue(
            diagonalDist > r,
            "expected squircle diagonal ($diagonalDist) > circle radius ($r)",
        )
    }
}
