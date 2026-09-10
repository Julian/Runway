package com.grayvines.runway

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grayvines.runway.data.Migrations
import com.grayvines.runway.data.RunwayDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The layout database is the user's most valuable state, so every schema version ships with a
 * migration from the one before, proven here against the exported schemas.
 */
@RunWith(AndroidJUnit4::class)
class RunwayDatabaseMigrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val file = File(instrumentation.targetContext.cacheDir, "migration-test.db")
    private val helper =
        MigrationTestHelper(
            instrumentation = instrumentation,
            file = file,
            driver = AndroidSQLiteDriver(),
            databaseClass = RunwayDatabase::class,
        )

    @After
    fun tearDown() {
        file.delete()
    }

    @Test
    fun theCurrentSchemaMatchesItsExport() = runBlocking {
        helper.createDatabase(RunwayDatabase.VERSION).close()
        helper.runMigrationsAndValidate(RunwayDatabase.VERSION).close()
    }

    @Test
    fun from1To2KeepsOneItemPerCellAndAllDrawerRows() = runBlocking {
        val db = helper.createDatabase(1)
        try {
            db.execSQL("INSERT INTO pages (container, page_index) VALUES ('HOME', 0)")
            for (id in 1..3) {
                db.execSQL(
                    "INSERT INTO items (id, kind, container, page_index, x, y, span_x, span_y) " +
                        "VALUES ($id, 'APP', 'HOME', 0, 0, 0, 1, 1)"
                )
            }
            for (id in 4..5) {
                db.execSQL(
                    "INSERT INTO items (id, kind, container, span_x, span_y) " +
                        "VALUES ($id, 'FOLDER', 'DRAWER', 1, 1)"
                )
            }
            // Two folders: one placed in the drawer (row 5) and once more in a cell, one whose
            // only placement is a duplicate of cell (0,0) and so goes, taking its apps along.
            db.execSQL("INSERT INTO folders (id, name) VALUES (10, 'kept'), (11, 'orphaned')")
            db.execSQL("UPDATE items SET folder_id = 10 WHERE id = 5")
            db.execSQL(
                "INSERT INTO items (id, kind, container, page_index, x, y, span_x, span_y, " +
                    "folder_id) VALUES (6, 'FOLDER', 'HOME', 0, 1, 0, 1, 1, 10), " +
                    "(7, 'FOLDER', 'HOME', 0, 0, 0, 1, 1, 11)"
            )
            db.execSQL(
                "INSERT INTO folder_apps (folder_id, component, profile, position) " +
                    "VALUES (10, 'a/.Main', 0, 0), (11, 'b/.Main', 0, 0)"
            )
        } finally {
            db.close()
        }
        val migrated = helper.runMigrationsAndValidate(2, Migrations.all)
        try {
            // The first of the three keeps cell (0,0); the drawer rows and the placed folder stay.
            assertEquals(listOf(1L, 4L, 5L, 6L), migrated.ids("SELECT id FROM items ORDER BY id"))
            assertEquals(listOf(10L), migrated.ids("SELECT id FROM folders"))
            assertEquals(listOf(10L), migrated.ids("SELECT folder_id FROM folder_apps"))
        } finally {
            migrated.close()
        }
    }

    @Test
    fun anOlderBuildOverANewerDatabaseOpensEmptyRatherThanNotAtAll() = runBlocking {
        val db = helper.createDatabase(RunwayDatabase.VERSION)
        try {
            db.execSQL("INSERT INTO pages (container, page_index) VALUES ('HOME', 0)")
            db.execSQL("PRAGMA user_version = ${RunwayDatabase.VERSION + 1}") // a build to come
        } finally {
            db.close()
        }
        val opened = RunwayDatabase.open(instrumentation.targetContext, file.absolutePath)
        try {
            assertEquals(
                emptyList<Long>(),
                opened.workspaceDao().allPages().map { it.index.toLong() },
            )
        } finally {
            opened.close()
        }
    }

    private fun SQLiteConnection.ids(sql: String): List<Long> {
        val rows = prepare(sql)
        return try {
            generateSequence { if (rows.step()) rows.getLong(0) else null }.toList()
        } finally {
            rows.close()
        }
    }
}
