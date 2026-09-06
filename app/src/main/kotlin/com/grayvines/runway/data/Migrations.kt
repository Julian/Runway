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
     * untouched.
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
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_items_container_page_index_x_y` " +
                        "ON `items` (`container`, `page_index`, `x`, `y`)"
                )
            }
        }

    val all: List<Migration>
        get() = listOf(from1To2)
}
