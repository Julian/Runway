package com.grayvines.runway.ui.drawer

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.system.apps.LabelOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DrawerSearchTest {
    private val apps =
        listOf("Calendar", "Camera", "Firefox", "Google Maps", "Lego", "Maps Go", "Signal")

    private fun search(query: String) = apps.matching(query) { it }

    @Test
    fun `nothing typed shows everything`() {
        assertEquals(apps, search(""))
        assertEquals(apps, search("   "))
    }

    @Test
    fun `typing narrows to labels containing it, whatever the case`() {
        assertEquals(listOf("Calendar", "Camera"), search("ca"))
        assertEquals(listOf("Signal"), search("SIG"))
        assertEquals(emptyList<String>(), search("zzz"))
    }

    @Test
    fun `accents and case are folded, as the drawer's order folds them`() {
        val accented = listOf("Éclair", "Café Noir", "Zoë")
        assertEquals(listOf("Éclair"), accented.matching("eclair") { it })
        assertEquals(listOf("Café Noir"), accented.matching("cafe") { it })
        assertEquals(listOf("Zoë"), accented.matching("ZOE") { it })
        assertEquals(listOf("Éclair"), accented.matching("éclair") { it })
    }

    @Test
    fun `a query with several words, or padding, matches as typed within the label`() {
        assertEquals(listOf("Google Maps"), search("google m"))
        assertEquals(listOf("Signal"), search("  sig "))
    }

    @Test
    fun `a label or word that starts with it comes before one that merely contains it`() {
        assertEquals(listOf("Google Maps", "Maps Go", "Lego"), search("go"))
    }

    @Test
    fun `work-profile twins sort after the personal profile so Go picks the personal app`() {
        val work = "Files" to AppRef("pkg.files/.Main", profile = 10L)
        val personal = "Files" to AppRef("pkg.files/.Main", profile = 0L)
        val otherPkg = "Files" to AppRef("pkg.other/.Main", profile = 0L)
        val sorted =
            listOf(otherPkg, work, personal)
                .sortedWith(LabelOrder.entryComparator({ it.first }, { it.second }))
        assertEquals(listOf(personal, otherPkg, work), sorted)
        assertEquals(personal, sorted.matching("files") { it.first }.first())
    }
}
