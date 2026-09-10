package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.Settings
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupTest {
    private val layout =
        Layout(
            homePages = 2,
            dockPages = 1,
            placements =
                listOf(
                    Placement(Container.DOCK, 0, 0, 0, app = AppRef("a/.Main", 0)),
                    Placement(
                        Container.HOME,
                        1,
                        2,
                        3,
                        folder =
                            Folder("Tools", listOf(AppRef("b/.Main", 0), AppRef("c/.Main", 10))),
                    ),
                ),
        )
    private val settings =
        Settings(columns = 5, drawerSwipe = DrawerSwipe.HIGH, drawerColumns = 6, searchTarget = "x")

    @Test
    fun `survives a trip through json`() {
        val backup = Backup(settings = settings, layout = layout)
        assertEquals(backup, Backup.fromJson(backup.toJson()))
    }

    @Test
    fun `reads what a newer or older file leaves out or adds`() {
        val text =
            """{"version":1,"settings":{"columns":4,"newSetting":true},""" +
                """"layout":{"home_pages":1,"dock_pages":1,"placements":[]}}"""
        val backup = Backup.fromJson(text)
        assertEquals(Settings(columns = 4), backup.settings)
        assertTrue(backup.layout.placements.isEmpty())
    }

    @Test
    fun `refuses what is not a backup, and a file from a newer Runway`() {
        assertThrows(IllegalArgumentException::class.java) { Backup.fromJson("hello") }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.fromJson("""{"settings":{}}""")
        }
        val newer = Backup(version = Backup.VERSION + 1, settings = settings, layout = layout)
        assertThrows(IllegalArgumentException::class.java) { Backup.fromJson(newer.toJson()) }
    }

    @Test
    fun `refuses a layout no restore should try, naming the fault`() {
        fun refusal(vararg placements: Placement, drawerFolders: List<Folder> = emptyList()) =
            assertThrows(IllegalArgumentException::class.java) {
                    Backup.fromJson(
                        Backup(
                                settings = Settings(columns = 4, rows = 5, dockSlots = 3),
                                layout = Layout(2, 1, placements.toList(), drawerFolders),
                            )
                            .toJson()
                    )
                }
                .message
                .orEmpty()
        val app = AppRef("a/.Main", 0)
        assertTrue("two things in one cell" in refusal(place(0, 0, 0, app), place(0, 0, 0, app)))
        assertTrue("no such page" in refusal(Placement(Container.HOME, 2, 0, 0, app = app)))
        assertTrue("off the grid" in refusal(Placement(Container.HOME, 0, 4, 0, app = app)))
        assertTrue("off the grid" in refusal(Placement(Container.HOME, 0, 0, 3, app = app)))
        assertTrue("off the grid" in refusal(Placement(Container.DOCK, 0, 3, 0, app = app)))
        assertTrue("no cells" in refusal(Placement(Container.DRAWER, 0, 0, 0, app = app)))
        assertTrue("exactly one" in refusal(Placement(Container.HOME, 0, 0, 0)))
        assertTrue(
            "no such drawer folder" in refusal(Placement(Container.HOME, 0, 0, 0, drawerFolder = 0))
        )
    }

    private fun place(page: Int, x: Int, y: Int, app: AppRef) =
        Placement(Container.HOME, page, x, y, app = app)

    @Test
    fun `keeps settings from a file within the allowed range`() {
        // An empty layout: the clamped grid is too small for the one the other tests use.
        val wild =
            Backup(
                settings = Settings(columns = 99, rows = 1, dockSlots = 0),
                layout = Layout(1, 1, emptyList()),
            )
        val read = Backup.fromJson(wild.toJson()).settings
        assertEquals(Settings.MAX_COLUMNS, read.columns)
        assertEquals(Settings.MIN_ROWS, read.rows)
        assertEquals(Settings.MIN_DOCK_SLOTS, read.dockSlots)
    }
}
