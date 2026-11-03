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

    /**
     * A folder's apps keep an order only once a hand has given them one: [FolderAppEntity.position]
     * becomes nullable and every row loses the position it had, which was the order the apps
     * happened to be added in rather than one anybody chose. Folders list their apps as the drawer
     * does until one is dragged into place.
     */
    val from2To3 =
        object : Migration(2, 3) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_folder_apps` (" +
                        "`folder_id` INTEGER NOT NULL, `component` TEXT NOT NULL, " +
                        "`profile` INTEGER NOT NULL, `position` INTEGER, " +
                        "PRIMARY KEY(`folder_id`, `component`, `profile`), " +
                        "FOREIGN KEY(`folder_id`) REFERENCES `folders`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "INSERT INTO `_new_folder_apps` (folder_id, component, profile, position) " +
                        "SELECT folder_id, component, profile, NULL FROM folder_apps"
                )
                connection.execSQL("DROP TABLE `folder_apps`")
                connection.execSQL("ALTER TABLE `_new_folder_apps` RENAME TO `folder_apps`")
            }
        }

    /**
     * Every placed item belongs to a page in `pages`, enforced by a foreign key, and every
     * container's pages are contiguous from 0. Any item sitting on a page that had no row in
     * `pages` gets its page (and any missing pages before it) created so the placement is kept.
     */
    val from3To4 =
        object : Migration(3, 4) {
            override suspend fun migrate(connection: SQLiteConnection) {
                connection.execSQL("DELETE FROM pages WHERE page_index < 0 OR container = 'DRAWER'")
                connection.execSQL("UPDATE items SET page_index = NULL WHERE container = 'DRAWER'")
                connection.execSQL(
                    "DELETE FROM items WHERE page_index IS NOT NULL AND page_index < 0"
                )
                connection.execSQL(
                    "INSERT OR IGNORE INTO pages (container, page_index) " +
                        "SELECT DISTINCT container, page_index FROM items WHERE page_index IS NOT NULL"
                )
                connection.execSQL(
                    "WITH RECURSIVE fill(container, idx, max_idx) AS (" +
                        "SELECT container, 0, MAX(page_index) FROM pages GROUP BY container " +
                        "UNION ALL " +
                        "SELECT container, idx + 1, max_idx FROM fill WHERE idx < max_idx" +
                        ") INSERT OR IGNORE INTO pages (container, page_index) " +
                        "SELECT container, idx FROM fill"
                )
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS `_new_items` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `container` TEXT NOT NULL, " +
                        "`page_index` INTEGER, `x` INTEGER, `y` INTEGER, " +
                        "`span_x` INTEGER NOT NULL, `span_y` INTEGER NOT NULL, " +
                        "`component` TEXT, `profile` INTEGER, `shortcut_id` TEXT, " +
                        "`folder_id` INTEGER, `app_widget_id` INTEGER, " +
                        "`provider` TEXT, `label_override` TEXT, " +
                        "FOREIGN KEY(`folder_id`) REFERENCES `folders`(`id`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE , " +
                        "FOREIGN KEY(`container`, `page_index`) REFERENCES `pages`(`container`, `page_index`) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE )"
                )
                connection.execSQL(
                    "INSERT INTO `_new_items` (" +
                        "id, kind, container, page_index, x, y, span_x, span_y, " +
                        "component, profile, shortcut_id, folder_id, app_widget_id, provider, label_override" +
                        ") SELECT " +
                        "id, kind, container, page_index, x, y, span_x, span_y, " +
                        "component, profile, shortcut_id, folder_id, app_widget_id, provider, label_override " +
                        "FROM items"
                )
                connection.execSQL("DROP TABLE `items`")
                connection.execSQL("ALTER TABLE `_new_items` RENAME TO `items`")
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_container_page_index` " +
                        "ON `items` (`container`, `page_index`)"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_items_folder_id` ON `items` (`folder_id`)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_items_container_page_index_x_y` " +
                        "ON `items` (`container`, `page_index`, `x`, `y`)"
                )
            }
        }

    val all: List<Migration>
        get() = listOf(from1To2, from2To3, from3To4)
}
