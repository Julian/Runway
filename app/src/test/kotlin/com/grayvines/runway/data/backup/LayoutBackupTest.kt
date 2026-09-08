package com.grayvines.runway.data.backup

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.Dropped
import com.grayvines.runway.data.RunwayDatabase
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.data.createDrawerFolder
import com.grayvines.runway.data.foldInto
import com.grayvines.runway.data.observeDrawerPlacements
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LayoutBackupTest {
    private val db =
        Room.inMemoryDatabaseBuilder<RunwayDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val repo = WorkspaceRepository(db)

    private val apps = (1..5).map { app(it) }

    private fun app(n: Int, profile: Long = 0) = AppRef("pkg$n/.Main", profile)

    @AfterEach fun tearDown() = db.close()

    /** One in the dock, a folder of two and a lone app on the first page, one on a second. */
    private suspend fun seed(): Layout {
        repo.autoFill(apps, columns = 3, pageRows = 1, dockSlots = 1)
        val (a, b) = repo.observe(Container.HOME).first().pages[0].items.sortedBy { it.x }
        repo.foldInto(targetId = b.id, dropped = Dropped.Item(a.id))
        return repo.layoutBackup()
    }

    @Test
    fun `the backup carries pages, apps and folders`() = runTest {
        val layout = seed()
        assertEquals(2, layout.homePages)
        assertEquals(1, layout.dockPages)
        assertEquals(
            listOf(app(1)),
            layout.placements.filter { it.container == Container.DOCK }.map { it.app },
        )
        val folder = layout.placements.single { it.folder != null }.folder!!
        assertEquals(listOf(app(3), app(2)), folder.apps)
        assertEquals(4, layout.placements.size)
    }

    @Test
    fun `restoring puts every placement back where it was`() = runTest {
        val layout = seed()
        repo.autoFill(emptyList(), columns = 3, pageRows = 1, dockSlots = 1)

        val restored = repo.restoreLayout(layout, apps.toSet())

        assertEquals(Restored(placed = 4, skipped = 0), restored)
        assertEquals(layout, repo.layoutBackup())
    }

    @Test
    fun `apps that are not installed are left out, and a folder with none of its apps goes too`() =
        runTest {
            val layout = seed()

            val restored = repo.restoreLayout(layout, setOf(app(1), app(4), app(5)))

            assertEquals(Restored(placed = 3, skipped = 2), restored)
            assertEquals(
                setOf(app(1), app(4), app(5)),
                repo.layoutBackup().placements.map { it.app }.toSet(),
            )
        }

    @Test
    fun `an app installed in one other profile matches by component alone`() = runTest {
        val layout = seed()
        val elsewhere = apps.map { it.copy(profile = 10) }.toSet()

        repo.restoreLayout(layout, elsewhere)

        assertEquals(elsewhere, repo.layoutBackup().placements.flatMap { it.apps() }.toSet())
    }

    @Test
    fun `a layout with no pages still leaves the first of each`() = runTest {
        repo.restoreLayout(
            Layout(homePages = 0, dockPages = 0, placements = emptyList()),
            emptySet(),
        )
        assertEquals(listOf(0), repo.observe(Container.HOME).first().pages.map { it.index })
        assertEquals(listOf(0), repo.observe(Container.DOCK).first().pages.map { it.index })
    }

    @Test
    fun `drawer folders travel too`() = runTest {
        seed()
        repo.createDrawerFolder(app(5))
        val layout = repo.layoutBackup()
        assertEquals(listOf(Folder("Folder", listOf(app(5)))), layout.drawerFolders)

        repo.restoreLayout(layout, apps.toSet())

        assertEquals(layout, repo.layoutBackup())
        assertEquals(1, repo.observeDrawerPlacements().first().size)
    }

    private fun Placement.apps() = listOfNotNull(app) + folder?.apps.orEmpty()
}
