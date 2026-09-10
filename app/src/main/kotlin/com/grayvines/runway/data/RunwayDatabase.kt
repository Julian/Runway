package com.grayvines.runway.data

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.driver.AndroidSQLiteDriver
import kotlinx.coroutines.Dispatchers

@Database(
    entities =
        [
            FolderEntity::class,
            FolderAppEntity::class,
            PageEntity::class,
            ItemEntity::class,
            HiddenAppEntity::class,
            BadgeMuteEntity::class,
        ],
    version = RunwayDatabase.VERSION,
    exportSchema = true,
)
abstract class RunwayDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        /** Bump together with a migration and its test in `RunwayDatabaseMigrationTest`. */
        const val VERSION = 2

        /**
         * Opens the layout database at [name] (a file name in the app's database directory, or an
         * absolute path). An older build over a newer database starts over with an empty layout: a
         * home screen that will not open at all is worse than one to be filled again.
         */
        fun open(context: Context, name: String = "runway.db"): RunwayDatabase =
            Room.databaseBuilder<RunwayDatabase>(context, name)
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*Migrations.all.toTypedArray())
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .build()
    }
}
