package com.grayvines.runway.data

import android.content.Context
import android.os.Process
import android.os.UserManager
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection
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
        const val VERSION = 4

        /**
         * Opens the layout database at [name] (a file name in the app's database directory, or an
         * absolute path). An older build over a newer database starts over with an empty layout: a
         * home screen that will not open at all is worse than one to be filled again. A database
         * made new hides Runway's own entry from the drawer, since the home menu opens it anyway.
         */
        fun open(context: Context, name: String = "runway.db"): RunwayDatabase {
            val appContext = context.applicationContext ?: context
            return Room.databaseBuilder<RunwayDatabase>(context, name)
                .setDriver(AndroidSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .addMigrations(*Migrations.all.toTypedArray())
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
                .addCallback(HiddenAtFirst { setOfNotNull(appContext.ownApp()) })
                .build()
        }
    }
}

/** Runway's own entry in the app list (its settings), as the database names it. */
private fun Context.ownApp(): AppRef? {
    val component = packageManager.getLaunchIntentForPackage(packageName)?.component ?: return null
    val profile =
        getSystemService(UserManager::class.java)?.getSerialNumberForUser(Process.myUserHandle())
            ?: return null
    return AppRef(component.flattenToString(), profile)
}

/** Hides Runway's own entry from the drawer lazily when a database is first created or reset. */
private class HiddenAtFirst(private val resolveApps: () -> Set<AppRef>) : RoomDatabase.Callback() {
    @Volatile private var pendingDestructiveHide = false

    override suspend fun onCreate(connection: SQLiteConnection) = hide(connection)

    // Room 3 invokes onDestructiveMigration after dropAllTables and before createAllTables, so
    // defer the insert until onOpen once the recreated tables exist.
    override suspend fun onDestructiveMigration(connection: SQLiteConnection) {
        pendingDestructiveHide = true
    }

    override suspend fun onOpen(connection: SQLiteConnection) {
        if (pendingDestructiveHide) {
            pendingDestructiveHide = false
            hide(connection)
        }
    }

    private fun hide(connection: SQLiteConnection) {
        resolveApps().forEach { app ->
            connection
                .prepare("INSERT OR IGNORE INTO hidden_apps (component, profile) VALUES (?, ?)")
                .use {
                    it.bindText(1, app.component)
                    it.bindLong(2, app.profile)
                    it.step()
                }
        }
    }
}
