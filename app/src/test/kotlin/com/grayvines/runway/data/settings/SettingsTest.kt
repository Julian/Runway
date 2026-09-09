package com.grayvines.runway.data.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SettingsTest {
    @Test
    fun `clamping brings every grid number within the settings screen's bounds`() {
        val clamped = Settings(columns = 1, rows = 2, dockSlots = 99, drawerColumns = 0).clamped()
        assertEquals(Settings.MIN_COLUMNS, clamped.columns)
        assertEquals(Settings.MIN_ROWS, clamped.rows)
        assertEquals(Settings.MAX_DOCK_SLOTS, clamped.dockSlots)
        assertEquals(Settings.MIN_COLUMNS, clamped.drawerColumns)
    }

    @Test
    fun `clamping leaves settings within bounds, and an unset drawer width, alone`() {
        val settings = Settings(columns = 5, rows = 8, dockSlots = 4, drawerColumns = null)
        assertEquals(settings, settings.clamped())
    }
}
