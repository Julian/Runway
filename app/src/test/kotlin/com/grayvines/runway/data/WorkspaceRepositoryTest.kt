package com.grayvines.runway.data

import androidx.room3.Room
import androidx.sqlite.SQLiteException
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.runway.model.Footprint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkspaceRepositoryTest {
    private val db =
        Room.inMemoryDatabaseBuilder<RunwayDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val repo = WorkspaceRepository(db)

    private fun apps(n: Int) = (1..n).map { AppRef("pkg$it/.Main", 0) }

    @AfterEach fun tearDown() = db.close()

    @Test
    fun `ensureInitialised creates one page per container, idempotently`() = runTest {
        repo.ensureInitialised()
        repo.ensureInitialised()
        assertEquals(listOf(0), repo.observe(Container.HOME).first().pages.map { it.index })
        assertEquals(listOf(0), repo.observe(Container.DOCK).first().pages.map { it.index })
    }

    @Test
    fun `autoFill fills the dock first, then home pages row-major`() = runTest {
        repo.autoFill(apps(6 + 14 + 1), columns = 7, pageRows = 2, dockSlots = 6)

        val dock = repo.observe(Container.DOCK).first().pages.single()
        assertEquals((0 until 6).toList(), dock.items.map { it.x })

        val home = repo.observe(Container.HOME).first().pages
        assertEquals(listOf(0, 1), home.map { it.index })
        assertEquals(14, home[0].items.size)
        val last = home[0].items.maxBy { (it.y ?: 0) * 7 + (it.x ?: 0) }
        assertEquals(6 to 1, last.x to last.y)
        assertEquals(1, home[1].items.size)
        assertTrue(
            home
                .flatMap { it.items }
                .all { it.kind == ItemKind.APP && it.container == Container.HOME }
        )
    }

    @Test
    fun `moveItem refuses a page that is gone, or a neighbour that has left it`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val (a, b) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }

        // The plan said page 1; it was pruned meanwhile.
        assertEquals(false, repo.moveItem(a.id, Container.HOME, 1, 0, 0, emptyMap()))
        // The plan pushes b aside; b went to the dock meanwhile (slot 1: slot 0 is taken).
        assertTrue(repo.moveItem(b.id, Container.DOCK, 0, 1, 0, emptyMap()))
        val refused =
            repo.moveItem(a.id, Container.HOME, 0, 1, 0, displaced = mapOf(b.id to Footprint(2, 0)))
        assertEquals(false, refused)

        val home = repo.observe(Container.HOME).first().pages.single().items
        assertEquals(0 to 0, home.single { it.id == a.id }.let { it.x to it.y }) // untouched
        assertTrue(repo.observe(Container.DOCK).first().pages.single().items.any { it.id == b.id })
    }

    @Test
    fun `moveItem relocates the item and its displaced neighbours together`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val page = repo.observe(Container.HOME).first().pages.single()
        val (a, b) = page.items.sortedBy { it.x } // a at (0,0), b at (1,0)

        repo.moveItem(a.id, Container.HOME, 0, 1, 0, displaced = mapOf(b.id to Footprint(2, 0)))

        val after = repo.observe(Container.HOME).first().pages.single().items.associateBy { it.id }
        assertEquals(1 to 0, after.getValue(a.id).let { it.x to it.y })
        assertEquals(2 to 0, after.getValue(b.id).let { it.x to it.y })

        repo.moveItem(a.id, Container.DOCK, 0, 1, 0, displaced = emptyMap())
        val dock = repo.observe(Container.DOCK).first().pages.single().items
        assertEquals(1 to 0, dock.single { it.id == a.id }.let { it.x to it.y })
        assertEquals(2, dock.size)
    }

    @Test
    fun `dropping an app on another makes a folder of the two in the target's cell`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val page = repo.observe(Container.HOME).first().pages.single()
        val (a, b) = page.items.sortedBy { it.x }

        repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))

        val items = repo.observe(Container.HOME).first().pages.single().items
        val folder = items.single()
        assertEquals(ItemKind.FOLDER, folder.kind)
        assertEquals(b.id, folder.id)
        assertEquals(b.x to b.y, folder.x to folder.y)
        assertEquals(null, folder.component)
        val content = repo.observeFolders().first().single()
        assertEquals(folder.folderId, content.id)
        assertEquals("Folder", content.name)
        assertEquals(listOf(b, a).map { AppRef(it.component!!, it.profile!!) }, content.apps)
    }

    @Test
    fun `dropping on a folder adds to it, the same app only once`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val page = repo.observe(Container.HOME).first().pages.single()
        val (a, b) = page.items.sortedBy { it.x }
        repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))

        val extra = AppRef("pkg9/.Main", 0)
        repo.foldInto(targetId = b.id, dropped = Dropped.App(extra))
        repo.foldInto(targetId = b.id, dropped = Dropped.App(extra))

        val content = repo.observeFolders().first().single()
        assertEquals(3, content.apps.size)
        assertEquals(extra, content.apps.last())
        assertEquals(1, repo.observe(Container.HOME).first().pages.single().items.size)
    }

    @Test
    fun `renaming a folder keeps the trimmed name, and a blank one changes nothing`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val (a, b) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
        repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))
        val folder = repo.observeFolders().first().single()

        assertTrue(repo.renameFolder(folder.id, "  Tools "))
        assertEquals("Tools", repo.observeFolders().first().single().name)
        assertEquals(false, repo.renameFolder(folder.id, "   "))
        assertEquals("Tools", repo.observeFolders().first().single().name)
    }

    @Test
    fun `folding the last item of a page away leaves the page for the drop to prune`() = runTest {
        repo.autoFill(apps(3), columns = 1, pageRows = 1, dockSlots = 1)
        val pages = repo.observe(Container.HOME).first().pages
        assertEquals(2, pages.size)
        val onFirst = pages[0].items.single()
        val onSecond = pages[1].items.single()

        repo.foldInto(targetId = onFirst.id, dropped = Dropped.Item(onSecond.id))
        assertEquals(2, repo.observe(Container.HOME).first().pages.size)

        repo.pruneEmptyPages()
        assertEquals(1, repo.observe(Container.HOME).first().pages.size)
    }

    @Test
    fun `unfolding puts the app in a cell of its own and takes it out of the folder`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val (a, b) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
        repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))
        val folder = repo.observeFolders().first().single()

        repo.unfold(folder.id, AppRef("pkg2/.Main", 0), Container.HOME, 0, 0, 0)

        assertEquals(listOf(AppRef("pkg3/.Main", 0)), repo.observeFolders().first().single().apps)
        val out = repo.observe(Container.HOME).first().pages.single().items.single { it.x == 0 }
        assertEquals(ItemKind.APP to "pkg2/.Main", out.kind to out.component)
    }

    @Test
    fun `unfolding the last app dissolves the folder and its placement`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val (a, b) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
        repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))
        val folder = repo.observeFolders().first().single()

        repo.unfold(folder.id, AppRef("pkg2/.Main", 0), Container.HOME, 0, 0, 0)
        repo.unfold(folder.id, AppRef("pkg3/.Main", 0), Container.HOME, 0, 2, 0)

        assertTrue(repo.observeFolders().first().isEmpty())
        val items = repo.observe(Container.HOME).first().pages.single().items
        assertEquals(setOf(ItemKind.APP), items.map { it.kind }.toSet())
        assertEquals(setOf(0, 2), items.map { it.x }.toSet())
    }

    @Test
    fun `folding out of one folder into another moves the app, and back onto its own it stays`() =
        runTest {
            repo.autoFill(apps(5), columns = 4, pageRows = 1, dockSlots = 1)
            val items = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
            val (b, d) = items[1] to items[3]
            repo.foldInto(targetId = b.id, dropped = Dropped.Item(items[0].id))
            repo.foldInto(targetId = d.id, dropped = Dropped.Item(items[2].id))
            val (first, second) = repo.observeFolders().first().sortedBy { it.id }
            val app = AppRef("pkg2/.Main", 0)

            assertTrue(repo.foldInto(targetId = d.id, dropped = Dropped.App(app), outOf = first.id))
            assertEquals(listOf(AppRef("pkg3/.Main", 0)), folderApps(first.id))
            assertEquals(
                listOf("pkg5/.Main", "pkg4/.Main", "pkg2/.Main").map { AppRef(it, 0) },
                folderApps(second.id),
            )

            assertTrue(
                repo.foldInto(targetId = d.id, dropped = Dropped.App(app), outOf = second.id)
            )
            assertEquals(
                listOf("pkg5/.Main", "pkg4/.Main", "pkg2/.Main").map { AppRef(it, 0) },
                folderApps(second.id),
            )
        }

    @Test
    fun `a drawer folder is a folder placed in the drawer, and an app is in one at most`() =
        runTest {
            repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
            val a = AppRef("pkg1/.Main", 0)
            val b = AppRef("pkg2/.Main", 0)

            val first = repo.createDrawerFolder(a)
            assertEquals(listOf(first), repo.observeDrawerPlacements().first().map { it.folderId })
            repo.addToDrawerFolder(first, b)
            assertEquals(listOf(a, b), folderApps(first))

            val second = repo.createDrawerFolder(b)
            assertEquals(listOf(a), folderApps(first))
            assertEquals(listOf(b), folderApps(second))

            // A new folder for the only app of another: that one has nothing left, and goes.
            val third = repo.createDrawerFolder(a)
            assertEquals(
                listOf(second, third),
                repo.observeDrawerPlacements().first().map { it.folderId },
            )
            repo.addToDrawerFolder(second, a) // and so does the third, the same way
            assertEquals(listOf(second), repo.observeDrawerPlacements().first().map { it.folderId })

            assertEquals(listOf(b, a), folderApps(second))

            repo.deleteFolder(second)
            assertTrue(repo.observeFolders().first().isEmpty())
            assertTrue(repo.observeDrawerPlacements().first().isEmpty())
        }

    @Test
    fun `a drawer folder placed in a cell is the same folder there and in the drawer`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val folderId = repo.createDrawerFolder(AppRef("pkg1/.Main", 0))
        repo.removeItem(repo.observe(Container.HOME).first().pages.single().items.first().id)

        repo.placeFolder(folderId, Container.HOME, 0, 0, 0)

        val placed = repo.observe(Container.HOME).first().pages.single().items.single { it.x == 0 }
        assertEquals(ItemKind.FOLDER to folderId, placed.kind to placed.folderId)
        assertTrue(repo.observeFolders().first().single().inDrawer)
        assertEquals(listOf(folderId), repo.observeDrawerPlacements().first().map { it.folderId })
    }

    private suspend fun folderApps(folderId: Long) =
        repo.observeFolders().first().single { it.id == folderId }.apps

    @Test
    fun `an uninstalled app leaves every folder, and an emptied folder goes with its placement`() =
        runTest {
            // The dock takes the first app; a, b and c are the three on the home page.
            repo.autoFill(apps(4), columns = 3, pageRows = 1, dockSlots = 1)
            val (a, b, c) =
                repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
            repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))

            repo.retainApps((apps(4) - AppRef("pkg2/.Main", 0)).toSet()) // a's app uninstalled
            assertEquals(
                listOf(AppRef("pkg3/.Main", 0)),
                repo.observeFolders().first().single().apps,
            )

            // b's app gone while the launcher was not running.
            repo.retainApps(setOf(AppRef("pkg1/.Main", 0), AppRef("pkg4/.Main", 0)))
            assertEquals(emptyList<FolderContent>(), repo.observeFolders().first())
            val left = repo.observe(Container.HOME).first().pages.single().items
            assertEquals(listOf(c.id), left.map { it.id }) // the folder placement went too
        }

    @Test
    fun `removing a folder placement or clearing the layout drops the folder itself`() = runTest {
        repo.autoFill(
            apps(5),
            columns = 4,
            pageRows = 1,
            dockSlots = 1,
        ) // one in the dock, four home
        val (a, b) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
        repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))
        repo.removeItem(b.id)
        assertEquals(emptyList<FolderContent>(), repo.observeFolders().first())

        val (x, y) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
        repo.foldInto(targetId = y.id, dropped = Dropped.Item(x.id))
        repo.clear()
        assertEquals(emptyList<FolderContent>(), repo.observeFolders().first())
    }

    @Test
    fun `a fold with nothing to fold leaves the target as it was`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        val (a, b) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
        assertEquals(false, repo.foldInto(targetId = b.id, dropped = Dropped.Item(999))) // gone
        assertEquals(false, repo.foldInto(targetId = b.id, dropped = Dropped.Item(b.id))) // itself
        val items = repo.observe(Container.HOME).first().pages.single().items
        assertEquals(ItemKind.APP, items.single { it.id == b.id }.kind)
        assertEquals(emptyList<FolderContent>(), repo.observeFolders().first())

        // A second placement of the same app dropped onto the first: just the extra placement goes.
        repo.addApp(AppRef(b.component!!, b.profile!!), Container.HOME, 0, 2, 0)
        val extra = repo.observe(Container.HOME).first().pages.single().items.single { it.x == 2 }
        assertEquals(false, repo.foldInto(targetId = b.id, dropped = Dropped.Item(extra.id)))
        val after = repo.observe(Container.HOME).first().pages.single().items
        assertEquals(ItemKind.APP, after.single { it.id == b.id }.kind)
        assertEquals(null, after.firstOrNull { it.id == extra.id })
        assertEquals(a.id, after.first { it.x == 0 }.id)
    }

    @Test
    fun `addPage appends an empty page and is idempotent`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
        repo.addPage(Container.HOME, 1)
        repo.addPage(Container.HOME, 1)
        val pages = repo.observe(Container.HOME).first().pages
        assertEquals(listOf(0, 1), pages.map { it.index })
        assertTrue(pages[1].items.isEmpty())
    }

    @Test
    fun `pruning drops only the empty pages after the last used one, on home and in the dock`() =
        runTest {
            repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
            repo.addPage(Container.HOME, 1) // empty, in the middle
            repo.addPage(Container.HOME, 2)
            repo.moveItem(2, Container.HOME, 2, 0, 0, emptyMap())
            repo.addPage(Container.HOME, 3) // trailing
            repo.addPage(Container.HOME, 4)
            repo.addPage(Container.DOCK, 1) // trailing too
            repo.pruneEmptyPages()
            assertEquals(
                listOf(0, 1, 2),
                repo.observe(Container.HOME).first().pages.map { it.index },
            )
            assertEquals(listOf(0), repo.observe(Container.DOCK).first().pages.map { it.index })
        }

    @Test
    fun `pruning never removes the first page`() = runTest {
        repo.ensureInitialised()
        repo.addPage(Container.HOME, 1)
        repo.pruneEmptyPages()
        assertEquals(listOf(0), repo.observe(Container.HOME).first().pages.map { it.index })
    }

    @Test
    fun `removeItem drops that placement only, and a page it leaves empty`() = runTest {
        repo.autoFill(apps(4), columns = 3, pageRows = 1, dockSlots = 1)
        // Dock: app 1. Home page 0: apps 2, 3, 4 fill the row... move 4 to a page of its own.
        repo.addPage(Container.HOME, 1)
        repo.moveItem(4, Container.HOME, 1, 0, 0, emptyMap())
        repo.removeItem(4)
        val home = repo.observe(Container.HOME).first()
        assertEquals(listOf(0), home.pages.map { it.index })
        assertEquals(listOf(2L, 3L), home.pages.single().items.map { it.id })
        assertEquals(1, repo.observe(Container.DOCK).first().pages.single().items.size)
    }

    @Test
    fun `retainApps drops placements of apps gone from a reported profile only`() = runTest {
        val work = AppRef("work/.Main", 10)
        repo.autoFill(apps(3) + work, columns = 3, pageRows = 1, dockSlots = 1)
        // App 2 was uninstalled while the launcher was down; the work profile is off entirely.
        repo.retainApps(setOf(apps(3)[0].let { AppRef(it.component, it.profile) }, apps(3)[2]))
        val placed =
            (repo.observe(Container.HOME).first().pages +
                    repo.observe(Container.DOCK).first().pages)
                .flatMap { it.items }
                .map { it.component }
        assertEquals(setOf("pkg1/.Main", "pkg3/.Main", "work/.Main"), placed.toSet())
    }

    @Test
    fun `retainApps prunes a page the removal leaves empty`() = runTest {
        repo.autoFill(apps(4), columns = 3, pageRows = 1, dockSlots = 1) // dock 1; page 0: 2,3,4
        repo.addPage(Container.HOME, 1)
        repo.moveItem(4, Container.HOME, 1, 0, 0, emptyMap())
        repo.retainApps(apps(3).toSet())
        assertEquals(listOf(0), repo.observe(Container.HOME).first().pages.map { it.index })
    }

    @Test
    fun `moveItem can swap two neighbours through each other's cells`() = runTest {
        repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1) // page 0: 2 at x0, 3 at x1
        repo.moveItem(2, Container.HOME, 0, 1, 0, displaced = mapOf(3L to Footprint(0, 0)))
        val byId = repo.observe(Container.HOME).first().pages.single().items.associateBy { it.id }
        assertEquals(1, byId[2L]?.x)
        assertEquals(0, byId[3L]?.x)
    }

    @Test
    fun `addApp beside an occupied cell moves the neighbour aside first`() = runTest {
        repo.autoFill(apps(2), columns = 3, pageRows = 1, dockSlots = 1)
        val neighbour = repo.observe(Container.HOME).first().pages.single().items.single()
        assertTrue(
            repo.addApp(
                apps(3)[2],
                Container.HOME,
                0,
                neighbour.x!!,
                neighbour.y!!,
                displaced = mapOf(neighbour.id to Footprint(2, 0)),
            )
        )
        val items = repo.observe(Container.HOME).first().pages.single().items
        assertEquals(2, items.size)
        assertEquals(2, items.single { it.id == neighbour.id }.x)
        // A neighbour that has left the page since the plan: nothing happens, nothing throws.
        repo.removeItem(neighbour.id)
        assertFalse(
            repo.addApp(apps(4)[3], Container.HOME, 0, 1, 0, mapOf(neighbour.id to Footprint(2, 0)))
        )
    }

    @Test
    fun `addApp places a new item, a second placement of the same app included`() = runTest {
        repo.autoFill(apps(2), columns = 3, pageRows = 1, dockSlots = 1) // dock: 1; home: 2 at x0
        repo.addApp(apps(2)[1], Container.HOME, 0, 2, 0)
        val items = repo.observe(Container.HOME).first().pages.single().items
        assertEquals(listOf(0, 2), items.map { it.x })
        assertEquals(listOf("pkg2/.Main", "pkg2/.Main"), items.map { it.component })
    }

    @Test
    fun `addApp onto a taken cell is refused by the database`() = runTest {
        repo.autoFill(apps(2), columns = 3, pageRows = 1, dockSlots = 1)
        assertThrows(SQLiteException::class.java) {
            runBlocking { repo.addApp(apps(2)[0], Container.HOME, 0, 0, 0) }
        }
    }

    @Test
    fun `clear leaves an empty first page in each container`() = runTest {
        repo.autoFill(apps(10), columns = 7, pageRows = 1, dockSlots = 6)
        repo.clear()
        val home = repo.observe(Container.HOME).first()
        assertEquals(1, home.pages.size)
        assertTrue(home.pages.single().items.isEmpty())
        assertTrue(repo.observe(Container.DOCK).first().pages.single().items.isEmpty())
    }
}
