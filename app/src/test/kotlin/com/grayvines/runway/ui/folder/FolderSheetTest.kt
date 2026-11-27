package com.grayvines.runway.ui.folder

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.ui.drag.Bounds
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FolderSheetTest {
    private val within = Bounds(left = 48f, top = 120f, right = 1032f, bottom = 2256f)
    private val size = IntSize(600, 480)

    @Test
    fun `centres the sheet directly over a folder away from the edges`() {
        val from = Bounds(left = 440f, top = 1000f, right = 640f, bottom = 1200f)
        assertEquals(
            IntOffset(540 - 300, 1100 - 240),
            sheetOffset(from, size, within),
        )
    }

    @Test
    fun `clamps a top-left folder inside the usable top-left bounds`() {
        val from = Bounds(left = 20f, top = 80f, right = 180f, bottom = 240f)
        assertEquals(
            IntOffset(within.left.toInt(), within.top.toInt()),
            sheetOffset(from, size, within),
        )
    }

    @Test
    fun `clamps a bottom-right dock folder inside the usable bottom-right bounds`() {
        val from = Bounds(left = 900f, top = 2150f, right = 1060f, bottom = 2300f)
        assertEquals(
            IntOffset(within.right.toInt() - size.width, within.bottom.toInt() - size.height),
            sheetOffset(from, size, within),
        )
    }

    @Test
    fun `handles a sheet larger than the usable bounds without throwing`() {
        val tinyWithin = Bounds(left = 48f, top = 120f, right = 300f, bottom = 300f)
        val from = Bounds(left = 100f, top = 100f, right = 200f, bottom = 200f)
        assertEquals(
            IntOffset(48, 120),
            sheetOffset(from, size, tinyWithin),
        )
    }

    @Test
    fun `PendingReorder suppresses stale pre-write orders but accepts membership or external changes`() {
        val a = AppRef("pkg.a/.Main", 0)
        val b = AppRef("pkg.b/.Main", 0)
        val c = AppRef("pkg.c/.Main", 0)
        val d = AppRef("pkg.d/.Main", 0)
        val pending = PendingReorder()

        assertFalse(pending.shouldIgnore(listOf(a, b, c)))

        pending.record(before = listOf(a, b, c), after = listOf(c, a, b))
        // Stale pre-write emission is ignored.
        assertTrue(pending.shouldIgnore(listOf(a, b, c)))
        // Expected order is accepted (will clear pending).
        assertFalse(pending.shouldIgnore(listOf(c, a, b)))
        // External removal, addition, or unrelated reorder breaks through immediately.
        assertFalse(pending.shouldIgnore(listOf(c, a)))
        assertFalse(pending.shouldIgnore(listOf(a, b, c, d)))
        assertFalse(pending.shouldIgnore(listOf(b, c, a)))

        // Back-to-back reorder suppresses both intermediate pre-write orders.
        pending.record(before = listOf(c, a, b), after = listOf(b, c, a))
        assertTrue(pending.shouldIgnore(listOf(a, b, c)))
        assertTrue(pending.shouldIgnore(listOf(c, a, b)))
        assertFalse(pending.shouldIgnore(listOf(b, c, a)))

        pending.clear()
        assertFalse(pending.shouldIgnore(listOf(a, b, c)))
    }
}
