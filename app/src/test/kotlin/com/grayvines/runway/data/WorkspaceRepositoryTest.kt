package com.grayvines.runway.data

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
    fun `removePackage drops every placement of that package in that profile only`() = runTest {
        val lookalike = AppRef("pkg1_x/.Main", 0) // an underscore is a LIKE wildcard
        repo.autoFill(
            apps(4) + AppRef("pkg1/.Other", 0) + AppRef("pkg1/.Main", 1) + lookalike,
            7,
            2,
            2,
        )
        repo.removePackage("pkg1", profile = 0)
        val left =
            (repo.observe(Container.HOME).first().pages +
                    repo.observe(Container.DOCK).first().pages)
                .flatMap { it.items }
                .map { it.component to it.profile }
                .toSet()
        assertEquals(
            setOf(
                "pkg2/.Main" to 0L,
                "pkg3/.Main" to 0L,
                "pkg4/.Main" to 0L,
                "pkg1/.Main" to 1L,
                "pkg1_x/.Main" to 0L,
            ),
            left,
        )
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
