package com.grayvines.runway.data

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

enum class Container {
    HOME,
    DOCK,
    DRAWER,
}

enum class ItemKind {
    APP,
    SHORTCUT,
    FOLDER,
    WIDGET,
}

@Entity(tableName = "folders")
data class FolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Int? = null,
)

@Entity(
    tableName = "folder_apps",
    primaryKeys = ["folder_id", "component", "profile"],
    foreignKeys =
        [
            ForeignKey(
                entity = FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["folder_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class FolderAppEntity(
    @ColumnInfo(name = "folder_id") val folderId: Long,
    val component: String,
    val profile: Long,
    val position: Int,
)

/** Explicit page list; the count is dynamic and pages are only ever added on purpose. */
@Entity(tableName = "pages", primaryKeys = ["container", "page_index"])
data class PageEntity(val container: Container, @ColumnInfo(name = "page_index") val index: Int)

/** One placement of a thing in a container. Which columns apply depends on [kind]/[container]. */
@Entity(
    tableName = "items",
    indices =
        [
            Index("container", "page_index"),
            Index("folder_id"),
            // One item per cell; drawer rows have no cell and nulls never collide.
            Index("container", "page_index", "x", "y", unique = true),
        ],
    foreignKeys =
        [
            ForeignKey(
                entity = FolderEntity::class,
                parentColumns = ["id"],
                childColumns = ["folder_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: ItemKind,
    val container: Container,
    @ColumnInfo(name = "page_index") val pageIndex: Int? = null,
    val x: Int? = null,
    val y: Int? = null,
    @ColumnInfo(name = "span_x") val spanX: Int = 1,
    @ColumnInfo(name = "span_y") val spanY: Int = 1,
    val component: String? = null,
    val profile: Long? = null,
    @ColumnInfo(name = "shortcut_id") val shortcutId: String? = null,
    @ColumnInfo(name = "folder_id") val folderId: Long? = null,
    @ColumnInfo(name = "app_widget_id") val appWidgetId: Int? = null,
    val provider: String? = null,
    @ColumnInfo(name = "label_override") val labelOverride: String? = null,
)

@Entity(tableName = "hidden_apps", primaryKeys = ["component", "profile"])
data class HiddenAppEntity(val component: String, val profile: Long)

@Entity(tableName = "badge_mutes", primaryKeys = ["component", "profile"])
data class BadgeMuteEntity(val component: String, val profile: Long)
