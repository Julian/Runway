package com.grayvines.runway.ui.drawer

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DrawerIndexTest {
    private val apps = listOf("Calendar", "camera", "Firefox", "Files", "1Password", "Signal")
    private val index = apps.index { it }

    @Test
    fun `each initial appears once, upper case, at its first app`() {
        assertEquals(listOf('C', 'F', '#', 'S'), index.map { it.letter })
        assertEquals(listOf(0, 2, 4, 5), index.map { it.position })
    }

    @Test
    fun `a finger down the strip lands on the letter under it`() {
        assertEquals('C', index.under(y = 0f, height = 400f)?.letter)
        assertEquals('F', index.under(y = 150f, height = 400f)?.letter)
        assertEquals('S', index.under(y = 399f, height = 400f)?.letter)
        assertEquals('S', index.under(y = 900f, height = 400f)?.letter) // past the end still counts
        assertEquals('C', index.under(y = -5f, height = 400f)?.letter)
        assertNull(emptyList<IndexEntry>().under(y = 10f, height = 400f))
    }
}
