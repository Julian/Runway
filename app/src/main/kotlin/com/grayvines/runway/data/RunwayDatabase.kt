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

        fun open(context: Context): RunwayDatabase =
            Room.databaseBuilder<RunwayDatabase>(context, "runway.db")
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*Migrations.all.toTypedArray())
                .build()
    }
}
