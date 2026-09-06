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
    version = 1,
    exportSchema = true,
)
abstract class RunwayDatabase : RoomDatabase() {
    abstract fun workspaceDao(): WorkspaceDao

    companion object {
        fun open(context: Context): RunwayDatabase =
            Room.databaseBuilder<RunwayDatabase>(context, "runway.db")
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
    }
}
