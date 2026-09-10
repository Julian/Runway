package com.grayvines.runway.ui.drawer

import com.grayvines.runway.system.apps.LabelOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DrawerIndexTest {
    private val apps =
        listOf("1Password", "Calendar", "camera", "Éclair", "Email", "Firefox", "Files", "Signal")
    private val index = apps.index { it }

    @Test
    fun `each initial appears once, upper case, at its first app`() {
        assertEquals(listOf('#', 'C', 'E', 'F', 'S'), index.map { it.letter })
        assertEquals(listOf(0, 1, 3, 5, 7), index.map { it.position })
    }

    @Test
    fun `a list in label order gives one entry per letter, with everything else first under one hash`() {
        val labels =
            listOf("zoo", "Éclair", "1Password", "apple", "~tilde", "Ωmega", "eclipse", "Zed")
        val sorted = labels.sortedWith(LabelOrder.comparator())
        assertEquals(
            listOf("~tilde", "1Password", "apple", "Éclair", "eclipse", "Zed", "zoo", "Ωmega"),
            sorted,
        )
        assertEquals(listOf('#', 'A', 'E', 'Z', 'Ω'), sorted.index { it }.map { it.letter })
    }

    @Test
    fun `a finger down the strip lands on the letter under it`() {
        val index =
            listOf("Calendar", "camera", "Firefox", "Files", "1Password", "Signal").index { it }
        assertEquals('C', index.under(y = 0f, height = 400f)?.letter)
        assertEquals('F', index.under(y = 150f, height = 400f)?.letter)
        assertEquals('S', index.under(y = 399f, height = 400f)?.letter)
        assertEquals('S', index.under(y = 900f, height = 400f)?.letter) // past the end still counts
        assertEquals('C', index.under(y = -5f, height = 400f)?.letter)
        assertNull(emptyList<IndexEntry>().under(y = 10f, height = 400f))
    }
}
