package com.grayvines.runway.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What order a folder holds its apps in: none of its own until a hand gives it one, and that one
 * from then on. What the order looks like on screen is the drawer's business; this is what is kept.
 */
class FolderOrderTest {
    private val db =
        Room.inMemoryDatabaseBuilder<RunwayDatabase>().setDriver(BundledSQLiteDriver()).build()
    private val repo = WorkspaceRepository(db)

    @AfterEach fun tearDown() = db.close()

    private fun app(n: Int) = AppRef("pkg$n/.Main", 0)

    /** A drawer folder holding [apps], added in the order given. */
    private suspend fun folderOf(vararg apps: AppRef): Long {
        val id = repo.createDrawerFolder(apps.first())
        apps.drop(1).forEach { repo.addToDrawerFolder(id, it) }
        return id
    }

    private suspend fun folder(id: Long) = repo.observeFolders().first().single { it.id == id }

    @Test
    fun `a folder takes no order of its own until a hand gives it one`() = runTest {
        val id = folderOf(app(2), app(1))
        assertFalse(folder(id).handSorted)

        repo.reorderFolder(id, listOf(app(1), app(2)))

        val sorted = folder(id)
        assertTrue(sorted.handSorted)
        assertEquals(listOf(app(1), app(2)), sorted.apps)
    }

    @Test
    fun `an app joining goes last of a hand-sorted folder, and takes no place in any other`() =
        runTest {
            val id = folderOf(app(2), app(1))
            repo.addToDrawerFolder(id, app(3))
            assertFalse(folder(id).handSorted)

            repo.reorderFolder(id, listOf(app(3), app(1), app(2)))
            repo.addToDrawerFolder(id, app(4))

            assertEquals(listOf(app(3), app(1), app(2), app(4)), folder(id).apps)
        }

    @Test
    fun `an order follows what the folder holds, not what it held`() = runTest {
        val id = folderOf(app(1), app(2), app(3))

        // An app the order does not mention (it joined after the sheet drew it) goes after those
        // it does.
        repo.reorderFolder(id, listOf(app(3), app(1)))
        assertEquals(listOf(app(3), app(1), app(2)), folder(id).apps)

        // One that has left the folder meanwhile is not in it to place.
        repo.removeFromFolder(id, app(1))
        repo.reorderFolder(id, listOf(app(1), app(2), app(3)))
        assertEquals(listOf(app(2), app(3)), folder(id).apps)
    }

    @Test
    fun `taking an app out leaves the rest as they were`() = runTest {
        val id = folderOf(app(1), app(2), app(3))
        repo.reorderFolder(id, listOf(app(3), app(2), app(1)))

        repo.removeFromFolder(id, app(2))

        assertEquals(listOf(app(3), app(1)), folder(id).apps)
    }
}
