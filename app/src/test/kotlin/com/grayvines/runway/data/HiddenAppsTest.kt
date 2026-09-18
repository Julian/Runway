package com.grayvines.runway.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Which apps the drawer leaves out, and what hiding one does to the rest of the layout. */
class HiddenAppsTest {
    private val db =
        Room.inMemoryDatabaseBuilder<RunwayDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val repo = WorkspaceRepository(db)

    @AfterEach fun tearDown() = db.close()

    private fun app(n: Int, profile: Long = 0) = AppRef("pkg$n/.Main", profile)

    private suspend fun hidden() = repo.observeHiddenApps().first()

    private suspend fun layout() =
        repo.observe(Container.HOME).first() to repo.observe(Container.DOCK).first()

    @Test
    fun `an app is hidden once however often it is hidden, and unhiding brings it back`() =
        runTest {
            repo.hideApp(app(1))
            repo.hideApp(app(1))
            repo.hideApp(app(2))
            assertEquals(setOf(app(1), app(2)), hidden())

            repo.unhideApp(app(1))
            repo.unhideApp(app(3)) // never hidden
            repo.unhideApp(app(2, profile = 10)) // the same app in another profile
            assertEquals(setOf(app(2)), hidden())
        }

    @Test
    fun `a hidden app leaves its drawer folder, and an emptied one waits for a prune`() = runTest {
        val folderId = repo.createDrawerFolder(app(1))
        repo.addToDrawerFolder(folderId, app(2))

        repo.hideApp(app(1))
        assertEquals(listOf(app(2)), repo.observeFolders().first().single().apps)

        repo.hideApp(app(2))
        assertEquals(emptyList<AppRef>(), repo.observeFolders().first().single().apps)
        assertEquals(listOf(folderId), repo.observeDrawerPlacements().first().map { it.folderId })

        repo.pruneEmptyFolders()
        assertTrue(repo.observeFolders().first().isEmpty())
    }

    @Test
    fun `hiding an app leaves its placements and home folders as they were`() = runTest {
        // The dock takes app 1; apps 2 and 3 fold into a folder on the home page.
        repo.autoFill(listOf(app(1), app(2), app(3)), columns = 3, pageRows = 1, dockSlots = 1)
        val (b, c) = repo.observe(Container.HOME).first().pages.single().items.sortedBy { it.x }
        repo.foldInto(targetId = c.id, dropped = Dropped.Item(b.id))
        val layout = layout()
        val folders = repo.observeFolders().first()

        repo.hideApp(app(1))
        repo.hideApp(app(2))

        assertEquals(layout, layout())
        assertEquals(folders, repo.observeFolders().first())
    }

    @Test
    fun `an uninstalled app is no longer hidden, within the profiles reported`() = runTest {
        val work = app(1, profile = 10)
        repo.hideApp(app(1))
        repo.hideApp(app(2))
        repo.hideApp(work)

        // App 2 was uninstalled; the work profile is off entirely.
        repo.retainApps(setOf(app(1), app(3)))

        assertEquals(setOf(app(1), work), hidden())
    }
}
