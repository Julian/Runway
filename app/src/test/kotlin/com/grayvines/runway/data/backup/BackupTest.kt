package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.settings.BadgeStyle
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.IconShape
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.settings.SwipeAction
import com.grayvines.runway.ui.settings.readBoundedUtf8
import java.io.ByteArrayInputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
                        0,
                        0,
                        0,
                        widget = Widget("w/.Clock", spanX = 2, spanY = 1),
                    ),
                    Placement(
                        Container.HOME,
                        1,
                        2,
                        3,
                        folder =
                            Folder(
                                "Tools",
                                listOf(AppRef("b/.Main", 0), AppRef("c/.Main", 10)),
                                handSorted = true,
                            ),
                    ),
                ),
            hiddenApps = listOf(AppRef("e/.Main", 0)),
            badgeMutes = listOf(AppRef("f/.Main", 0)),
        )
    private val settings =
        Settings(
            columns = 5,
            iconShape = IconShape.SQUIRCLE,
            badgeStyle = BadgeStyle.DOT,
            drawerSwipe = DrawerSwipe.HIGH,
            drawerColumns = 6,
            searchHiddenApps = true,
            wallpaperParallax = false,
            searchTarget = "x",
            swipeRight = SwipeAction.OpenApp(AppRef("d/.Main", 10)),
        )

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
        assertNull(backup.layout.hiddenApps) // from before hidden apps, not "none hidden"
        assertNull(backup.layout.badgeMutes) // from before badge mutes, not "none muted"
    }

    @Test
    fun `refuses what is not a backup, and a file from a newer Runway`() {
        assertThrows(IllegalArgumentException::class.java) { Backup.fromJson("hello") }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.fromJson("""{"settings":{}}""")
        }
        val newer = Backup(version = Backup.VERSION + 1, settings = settings, layout = layout)
        assertTrue(
            "newer Runway" in
                assertThrows(IllegalArgumentException::class.java) {
                        Backup.fromJson(newer.toJson())
                    }
                    .message
                    .orEmpty()
        )
        val zero = Backup(version = 0, settings = settings, layout = layout)
        assertThrows(IllegalArgumentException::class.java) { Backup.fromJson(zero.toJson()) }
        val negative = Backup(version = -1, settings = settings, layout = layout)
        assertThrows(IllegalArgumentException::class.java) { Backup.fromJson(negative.toJson()) }
    }

    @Test
    fun `reads a backup with unknown future enum values or swipe actions by falling back to defaults`() {
        val text =
            """{"version":1,"settings":{"columns":5,"iconShape":"TEARDROP",""" +
                """"badgeStyle":"GLOW","drawerSwipe":"EXTREME",""" +
                """"swipeRight":{"type":"open_assistant"}},""" +
                """"layout":{"home_pages":1,"dock_pages":1,"placements":[]}}"""
        val backup = Backup.fromJson(text)
        assertEquals(5, backup.settings.columns)
        assertEquals(IconShape.SYSTEM, backup.settings.iconShape)
        assertEquals(BadgeStyle.COUNT, backup.settings.badgeStyle)
        assertEquals(DrawerSwipe.MEDIUM, backup.settings.drawerSwipe)
        assertNull(backup.settings.swipeRight)
    }

    @Test
    fun `accepts backups exported after shrinking the grid while keeping out-of-grid placements`() {
        val shrunk =
            Backup(
                settings = Settings(columns = 4, rows = 5, dockSlots = 3),
                layout =
                    Layout(
                        homePages = 1,
                        dockPages = 1,
                        placements =
                            listOf(
                                Placement(Container.HOME, 0, 6, 7, app = AppRef("a/.Main", 0)),
                                Placement(Container.DOCK, 0, 5, 0, app = AppRef("b/.Main", 0)),
                            ),
                    ),
            )
        assertEquals(shrunk, Backup.fromJson(shrunk.toJson()))
    }

    private fun refusal(
        vararg placements: Placement,
        drawerFolders: List<Folder> = emptyList(),
        hiddenApps: List<AppRef>? = null,
    ) =
        assertThrows(IllegalArgumentException::class.java) {
                Backup.fromJson(
                    Backup(
                            settings = Settings(columns = 4, rows = 5, dockSlots = 3),
                            layout = Layout(2, 1, placements.toList(), drawerFolders, hiddenApps),
                        )
                        .toJson()
                )
            }
            .message
            .orEmpty()

    @Test
    fun `refuses invalid cells, pages, overlaps and widgets, naming the fault`() {
        val app = AppRef("a/.Main", 0)
        val widget = Widget("w/.One", spanX = 2, spanY = 2)
        assertTrue("two things in one cell" in refusal(place(0, 0, 0, app), place(0, 0, 0, app)))
        assertTrue(
            "two things in one cell" in
                refusal(Placement(Container.HOME, 0, 0, 0, widget = widget), place(0, 1, 1, app))
        )
        assertTrue("no such page" in refusal(Placement(Container.HOME, 2, 0, 0, app = app)))
        assertTrue("off the grid" in refusal(Placement(Container.HOME, 0, -1, 0, app = app)))
        assertTrue(
            "off the grid" in
                refusal(Placement(Container.HOME, 0, Settings.MAX_COLUMNS, 0, app = app))
        )
        assertTrue(
            "off the grid" in
                refusal(Placement(Container.HOME, 0, 0, Settings.MAX_PAGE_ROWS, app = app))
        )
        assertTrue(
            "off the grid" in
                refusal(Placement(Container.DOCK, 0, Settings.MAX_DOCK_SLOTS, 0, app = app))
        )
        assertTrue(
            "off the grid" in
                refusal(Placement(Container.HOME, 0, Settings.MAX_COLUMNS - 1, 0, widget = widget))
        )
        assertTrue(
            "off the grid" in
                refusal(
                    Placement(Container.HOME, 0, 0, Settings.MAX_PAGE_ROWS - 1, widget = widget)
                )
        )
        assertTrue(
            "at least 1x1" in
                refusal(Placement(Container.HOME, 0, 0, 0, widget = Widget("w/.One", 0, 1)))
        )
        assertTrue(
            "home screen only" in refusal(Placement(Container.DOCK, 0, 0, 0, widget = widget))
        )
        assertTrue(
            "no provider" in refusal(Placement(Container.HOME, 0, 0, 0, widget = Widget(" ")))
        )
        assertTrue(
            "no provider" in refusal(Placement(Container.HOME, 0, 0, 0, widget = Widget("plain")))
        )
        assertTrue(
            "no provider" in refusal(Placement(Container.HOME, 0, 0, 0, widget = Widget("/")))
        )
    }

    @Test
    fun `refuses malformed components, folders and drawer-folder conflicts`() {
        val app = AppRef("a/.Main", 0)
        val widget = Widget("w/.One", spanX = 2, spanY = 2)
        assertTrue(
            "invalid component" in
                refusal(Placement(Container.HOME, 0, 0, 0, app = AppRef("bad", 0)))
        )
        assertTrue(
            "folder name must not be blank" in
                refusal(Placement(Container.HOME, 0, 0, 0, folder = Folder("   ", listOf(app))))
        )
        assertTrue(
            "folder name is too long" in
                refusal(
                    Placement(
                        Container.HOME,
                        0,
                        0,
                        0,
                        folder = Folder("x".repeat(MAX_FOLDER_NAME_LENGTH + 1), listOf(app)),
                    )
                )
        )
        assertTrue("no cells" in refusal(Placement(Container.DRAWER, 0, 0, 0, app = app)))
        assertTrue("exactly one" in refusal(Placement(Container.HOME, 0, 0, 0)))
        assertTrue(
            "exactly one" in refusal(Placement(Container.HOME, 0, 0, 0, app = app, widget = widget))
        )
        assertTrue(
            "no such drawer folder" in refusal(Placement(Container.HOME, 0, 0, 0, drawerFolder = 0))
        )
        assertTrue(
            "hidden from the drawer" in
                refusal(
                    drawerFolders = listOf(Folder("Tools", listOf(app))),
                    hiddenApps = listOf(app),
                )
        )
        assertTrue(
            "duplicate" in refusal(drawerFolders = listOf(Folder("Tools", listOf(app, app))))
        )
        assertTrue(
            "more than one drawer folder" in
                refusal(
                    drawerFolders =
                        listOf(Folder("Tools", listOf(app)), Folder("More", listOf(app)))
                )
        )
    }

    @Test
    fun `reports newer version even when future schema changes settings, and refuses bad version types`() {
        val futureText =
            """{"version":${Backup.VERSION + 1},"settings":{"iconShape":"FUTURE","unknown":1},""" +
                """"layout":{"home_pages":1,"dock_pages":1,"placements":[]}}"""
        assertTrue(
            "newer Runway" in
                assertThrows(IllegalArgumentException::class.java) { Backup.fromJson(futureText) }
                    .message
                    .orEmpty()
        )
        assertThrows(IllegalArgumentException::class.java) {
            Backup.fromJson(
                """{"version":"1","settings":{},"layout":{"home_pages":1,"dock_pages":1,"placements":[]}}"""
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Backup.fromJson(
                """{"version":null,"settings":{},"layout":{"home_pages":1,"dock_pages":1,"placements":[]}}"""
            )
        }
    }

    @Test
    fun `refuses negative or excessive page counts`() {
        assertTrue(
            "negative page counts" in
                assertThrows(IllegalArgumentException::class.java) {
                        Layout(-1, 1, emptyList()).validate()
                    }
                    .message
                    .orEmpty()
        )
        assertTrue(
            "too many pages" in
                assertThrows(IllegalArgumentException::class.java) {
                        Layout(MAX_PAGES + 1, 1, emptyList()).validate()
                    }
                    .message
                    .orEmpty()
        )
        assertTrue(
            "too many pages" in
                assertThrows(IllegalArgumentException::class.java) {
                        Layout(1, MAX_PAGES + 1, emptyList()).validate()
                    }
                    .message
                    .orEmpty()
        )
        assertTrue(
            "too many drawer folders" in
                assertThrows(IllegalArgumentException::class.java) {
                        val app = AppRef("a/.Main", 0)
                        val folders = List(MAX_DRAWER_FOLDERS + 1) { Folder("F$it", listOf(app)) }
                        Layout(1, 1, emptyList(), drawerFolders = folders).validate()
                    }
                    .message
                    .orEmpty()
        )
    }

    @Test
    fun `readBoundedUtf8 rejects streams larger than the limit`() {
        val exact = ByteArray(16) { 'a'.code.toByte() }
        assertEquals("a".repeat(16), ByteArrayInputStream(exact).readBoundedUtf8(maxBytes = 16))
        val tooLarge = ByteArray(17) { 'a'.code.toByte() }
        assertTrue(
            "too large" in
                assertThrows(IllegalArgumentException::class.java) {
                        ByteArrayInputStream(tooLarge).readBoundedUtf8(maxBytes = 16)
                    }
                    .message
                    .orEmpty()
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
