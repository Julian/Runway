package com.grayvines.runway

import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.grayvines.runway.data.RunwayDatabase
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
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
}
