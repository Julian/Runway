package com.grayvines.runway.ui.drag

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DropAreaTrackerTest {
    private val changes = mutableListOf<DropAreas>()
    private val tracker = DropAreaTracker { changes += it }
    private val page0 = Bounds(0f, 0f, 100f, 100f)
    private val page1 = Bounds(100f, 0f, 200f, 100f)

    @Test
    fun `the shown page's bounds are the home area, even when reported before it is shown`() {
        tracker.homePagePositioned(0, page0, 4, 3)
        tracker.homePagePositioned(1, page1, 4, 3)
        assertEquals(page0, tracker.areas.home)
        tracker.homePageShown(1, 4, 3)
        assertEquals(page1, tracker.areas.home)
        assertEquals(1, tracker.areas.homePage)
    }

    @Test
    fun `a page settling after being shown updates the area`() {
        tracker.homePageShown(1, 4, 3)
        assertEquals(null, tracker.areas.home)
        tracker.homePagePositioned(1, page1, 4, 3)
        assertEquals(page1, tracker.areas.home)
    }

    @Test
    fun `the dock tracks its own pages the same way`() {
        val dock0 = Bounds(0f, 100f, 100f, 120f)
        val dock1 = Bounds(100f, 100f, 200f, 120f)
        tracker.dockPagePositioned(0, dock0, 3)
        tracker.dockPagePositioned(1, dock1, 3)
        assertEquals(dock0, tracker.areas.dock)
        tracker.dockPageShown(1, 3)
        assertEquals(dock1, tracker.areas.dock)
        assertEquals(1, tracker.areas.dockPage)
        tracker.homePageShown(1, 4, 3) // the pagers are independent
        assertEquals(dock1, tracker.areas.dock)
    }

    @Test
    fun `changes are reported once each and only when something changed`() {
        tracker.homePagePositioned(0, page0, 4, 3)
        tracker.homePagePositioned(0, page0, 4, 3)
        tracker.dockPagePositioned(0, Bounds(0f, 100f, 100f, 120f), 3)
        assertEquals(2, changes.size)
        assertEquals(3, changes.last().dockSlots)
    }
}
