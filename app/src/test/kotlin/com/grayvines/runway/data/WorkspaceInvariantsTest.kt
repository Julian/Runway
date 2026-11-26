package com.grayvines.runway.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.grayvines.runway.model.PackageKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** State invariants for reconciliation, drawer folders, unfolding, pages, and widget writes. */
class WorkspaceInvariantsTest {
    private val db =
        Room.inMemoryDatabaseBuilder<RunwayDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val repo = WorkspaceRepository(db)

    @AfterEach fun tearDown() = db.close()

    private fun apps(n: Int) = (1..n).map { AppRef("pkg$it/.Main", 0) }

    @Test
    fun `retainApps preserves temporarily unavailable packages and unrelated empty folders`() =
        runTest {
            val a = AppRef("pkg1/.Main", 0)
            val sd = AppRef("pkg.sd/.Main", 0)
            val gone = AppRef("pkg.gone/.Main", 0)
            val sheet = AppRef("pkg.sheet/.Main", 0)
            repo.autoFill(listOf(a, sd, gone), columns = 4, pageRows = 1, dockSlots = 1)
            val sdFolder = repo.createDrawerFolder(sd)
            val sheetFolder = repo.createDrawerFolder(sheet)
            repo.removeFromFolder(sheetFolder, sheet) // emptied in an open sheet
            repo.hideApp(AppRef("pkg.sd/.Other", 0))
            repo.muteBadges(sd)

            repo.retainApps(
                installed = setOf(a),
                preservePackages = setOf(PackageKey("pkg.sd", 0)),
            )

            val placed =
                (repo.observe(Container.HOME).first().pages +
                        repo.observe(Container.DOCK).first().pages)
                    .flatMap { it.items }
                    .mapNotNull { it.appRef() }
                    .toSet()
            assertEquals(setOf(a, sd), placed)
            assertEquals(
                listOf(sd),
                repo.observeFolders().first().single { it.id == sdFolder }.apps,
            )
            assertEquals(
                setOf(sdFolder, sheetFolder),
                repo.observeFolders().first().map { it.id }.toSet(),
            )
            assertEquals(setOf(AppRef("pkg.sd/.Other", 0)), repo.observeHiddenApps().first())
            assertEquals(setOf(sd), repo.observeBadgeMutes().first())
        }

    @Test
    fun `guards invalid pages, drawer relocations, missing folders, stale unfolds, and bad widgets`() =
        runTest {
            repo.autoFill(apps(3), columns = 3, pageRows = 1, dockSlots = 1)
            val homeItem = repo.observe(Container.HOME).first().pages.single().items.first()
            val drawerFolderId = repo.createDrawerFolder(apps(3)[0])
            val drawerPlacementId = repo.observeDrawerPlacements().first().single().id

            // Invalid page requests are ignored; drawer placements cannot be relocated onto pages.
            repo.addPage(Container.DRAWER, 0)
            repo.addPage(Container.HOME, -1)
            assertFalse(repo.moveItem(homeItem.id, Container.DRAWER, 0, 0, 0, emptyMap()))
            assertFalse(repo.moveItem(drawerPlacementId, Container.HOME, 0, 2, 0, emptyMap()))

            // Missing folder ids fail safely without FK errors.
            assertFalse(repo.placeFolder(9999L, Container.HOME, 0, 2, 0))
            repo.addToDrawerFolder(9999L, apps(3)[1])

            // Unfolding an app not in the folder does not create a ghost placement.
            assertFalse(repo.unfold(drawerFolderId, apps(3)[2], Container.HOME, 0, 2, 0))
            assertEquals(2, repo.observe(Container.HOME).first().pages.single().items.size)

            // Deleting a placed folder drops trailing empty pages left behind.
            repo.addPage(Container.HOME, 1)
            assertTrue(repo.placeFolder(drawerFolderId, Container.HOME, 1, 0, 0))
            repo.deleteFolder(drawerFolderId)
            assertEquals(listOf(0), repo.observe(Container.HOME).first().pages.map { it.index })

            // Non-positive widget spans return null; invalid new remap ids are ignored while
            // valid new ids for unmatched or invalid old ids are returned as unused to release.
            assertNull(repo.addWidget(5, "w/.Bad", page = 0, x = 2, y = 0, spanX = 0, spanY = 1))
            assertNull(repo.addWidget(5, "w/.Bad", page = 0, x = 2, y = 0, spanX = 1, spanY = -1))
            assertEquals(setOf(5, 7), repo.remapWidgets(mapOf(0 to 5, 5 to 0, -1 to 7)))
        }

    @Test
    fun `creating or pruning without deleting any folder leaves an explicit empty page alone`() =
        runTest {
            repo.ensureInitialised()
            repo.addPage(Container.HOME, 1)
            val id = repo.createDrawerFolder(apps(2)[0])
            repo.addToDrawerFolder(id, apps(2)[1])
            repo.pruneEmptyFolders()

            assertEquals(listOf(0, 1), repo.observe(Container.HOME).first().pages.map { it.index })
        }

    @Test
    fun `retainApps removing one app from a multi-app folder keeps an explicit trailing empty page`() =
        runTest {
            val a = apps(2)[0]
            val b = apps(2)[1]
            repo.ensureInitialised()
            repo.addPage(Container.HOME, 1)
            val folderId = repo.createDrawerFolder(a)
            repo.addToDrawerFolder(folderId, b)

            repo.retainApps(setOf(a))

            assertEquals(listOf(a), repo.observeFolders().first().single().apps)
            assertEquals(listOf(0, 1), repo.observe(Container.HOME).first().pages.map { it.index })
        }
}
