package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.settings.Settings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Everything a fresh install needs to become this one again: the settings, and the layout as
 * placements of apps and folders. Widgets and shortcuts are not carried: their ids belong to the
 * device they were made on. The file is JSON, so it can be read, kept and edited by hand.
 */
@Serializable
data class Backup(val version: Int = VERSION, val settings: Settings, val layout: Layout) {
    fun toJson(): String = format.encodeToString(this)

    companion object {
        /** Bump when a reader of the previous version could not make sense of the file. */
        const val VERSION = 1

        private val format = Json {
            ignoreUnknownKeys = true // a newer Runway may have added settings
            encodeDefaults = true // a reader should not need to know the defaults
            prettyPrint = true
        }

        /** Throws [IllegalArgumentException] for anything that is not a backup this can read. */
        fun fromJson(text: String): Backup {
            val backup =
                try {
                    format.decodeFromString<Backup>(text)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException("not a Runway backup", e)
                }
            require(backup.version <= VERSION) { "made by a newer Runway (${backup.version})" }
            return backup.copy(settings = backup.settings.clamped())
        }

        /** Values from a file stay within what the settings screen would allow. */
        private fun Settings.clamped() =
            copy(
                columns = columns.coerceIn(Settings.MIN_COLUMNS, Settings.MAX_COLUMNS),
                rows = rows.coerceIn(Settings.MIN_ROWS, Settings.MAX_ROWS),
                dockSlots = dockSlots.coerceIn(Settings.MIN_DOCK_SLOTS, Settings.MAX_DOCK_SLOTS),
                drawerColumns = drawerColumns?.coerceIn(Settings.MIN_COLUMNS, Settings.MAX_COLUMNS),
            )
    }
}

/** The pages of each container and what sits on them. */
@Serializable
data class Layout(
    @SerialName("home_pages") val homePages: Int,
    @SerialName("dock_pages") val dockPages: Int,
    val placements: List<Placement>,
    /** The folders atop the drawer, which have no cell. */
    @SerialName("drawer_folders") val drawerFolders: List<Folder> = emptyList(),
)

/**
 * One cell's content: an [app], a [folder] of apps of its own, or [drawerFolder], an index into
 * [Layout.drawerFolders], for a drawer folder placed in a cell as well.
 */
@Serializable
data class Placement(
    val container: Container,
    val page: Int,
    val x: Int,
    val y: Int,
    val app: AppRef? = null,
    val folder: Folder? = null,
    @SerialName("drawer_folder") val drawerFolder: Int? = null,
)

@Serializable data class Folder(val name: String, val apps: List<AppRef>)

/** How a restore went: the placements made, and the apps left out for not being installed. */
data class Restored(val placed: Int, val skipped: Int)
