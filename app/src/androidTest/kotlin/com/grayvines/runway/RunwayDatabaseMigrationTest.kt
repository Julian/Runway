package com.grayvines.runway

import androidx.room3.testing.MigrationTestHelper
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
        } finally {
            db.close()
        }
        val migrated = helper.runMigrationsAndValidate(2, Migrations.all)
        try {
            val rows = migrated.prepare("SELECT id FROM items ORDER BY id")
            val ids = generateSequence { if (rows.step()) rows.getLong(0) else null }.toList()
            rows.close()
            assertEquals(listOf(1L, 4L, 5L), ids) // the first of the three keeps the cell
        } finally {
            migrated.close()
        }
    }
}
