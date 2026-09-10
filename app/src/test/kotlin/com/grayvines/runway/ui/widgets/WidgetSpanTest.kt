package com.grayvines.runway.ui.widgets

import com.grayvines.runway.model.GridSize
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class WidgetSpanTest {
    private val grid = GridSize(columns = 4, rows = 5)

    @Test
    fun `the designed size comes first, then the smallest the minimum allows`() {
        val spans =
            spansFor(WidgetSpan(2, 1), 80f, 40f, cellWidthDp = 100f, cellHeightDp = 100f, grid)
        assertEquals(listOf(WidgetSpan(2, 1), WidgetSpan(1, 1)), spans)
    }

    @Test
    fun `a widget with no designed size tries its smallest only`() {
        assertEquals(listOf(WidgetSpan(2, 2)), spansFor(null, 150f, 110f, 100f, 100f, grid))
    }

    @Test
    fun `a designed size the grid cannot hold is skipped`() {
        assertEquals(
            listOf(WidgetSpan(1, 1)),
            spansFor(WidgetSpan(5, 1), 80f, 40f, 100f, 100f, grid),
        )
    }

    @Test
    fun `a designed size smaller than the minimum grows to it, and identical sizes merge`() {
        assertEquals(
            listOf(WidgetSpan(2, 1)),
            spansFor(WidgetSpan(1, 1), 150f, 40f, 100f, 100f, grid),
        )
    }

    @Test
    fun `a minimum larger than the grid is clamped to it`() {
        assertEquals(listOf(WidgetSpan(4, 5)), spansFor(null, 1000f, 1000f, 100f, 100f, grid))
    }
}
