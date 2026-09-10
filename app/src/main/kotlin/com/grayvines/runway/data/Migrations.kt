package com.grayvines.runway.data

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Every schema version's step from the one before, each proven by `RunwayDatabaseMigrationTest`.
 */
object Migrations {
    /**
     * One item per cell: drop later duplicates, then forbid them. Drawer rows (no cell) are
     * untouched. A folder whose only placement was a dropped duplicate goes with it, apps and all,
     * as it would had the placement been removed by hand.
     */
    val from1To2 =
        object : Migration(1, 2) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "DELETE FROM items " +
                        "WHERE page_index IS NOT NULL AND x IS NOT NULL AND y IS NOT NULL " +
                        "AND id NOT IN (SELECT MIN(id) FROM items " +
                        "WHERE page_index IS NOT NULL AND x IS NOT NULL AND y IS NOT NULL " +
                        "GROUP BY container, page_index, x, y)"
                )
                // Spelled out rather than left to the cascade: foreign keys are not necessarily
                // enforced while a migration runs.
                connection.execSQL(
                    "DELETE FROM folder_apps WHERE folder_id NOT IN " +
                        "(SELECT folder_id FROM items WHERE folder_id IS NOT NULL)"
                )
                connection.execSQL(
                    "DELETE FROM folders WHERE id NOT IN " +
                        "(SELECT folder_id FROM items WHERE folder_id IS NOT NULL)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_items_container_page_index_x_y` " +
                        "ON `items` (`container`, `page_index`, `x`, `y`)"
                )
            }
        }

    val all: List<Migration>
        get() = listOf(from1To2)
}
