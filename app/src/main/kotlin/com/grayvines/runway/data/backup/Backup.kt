package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.settings.clamped
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
            val settings = backup.settings.clamped()
            backup.layout.validate(settings)
            return backup.copy(settings = settings)
        }
    }
}

/**
 * Throws [IllegalArgumentException], naming the first fault, for a layout no restore should try: a
 * cell off the grid [settings] describes, a page the file does not have, two things in one cell, a
 * placement that is not exactly one of an app, a folder or a drawer folder, or a drawer folder
 * index there is no folder for. Checked whole before anything is written, so a bad file changes
 * nothing.
 */
internal fun Layout.validate(settings: Settings) {
    require(homePages >= 0 && dockPages >= 0) { "negative page counts" }
    val cells = mutableSetOf<List<Int>>()
    placements.forEachIndexed { i, p ->
        val where = "placement ${i + 1} (${p.container} page ${p.page}, cell ${p.x},${p.y})"
        val pages = if (p.container == Container.DOCK) dockPages else homePages
        val (columns, rows) =
            when (p.container) {
                Container.HOME -> settings.columns to settings.pageRows
                Container.DOCK -> settings.dockSlots to 1
                Container.DRAWER ->
                    throw IllegalArgumentException("$where: the drawer has no cells")
            }
        require(p.page in 0 until pages) { "$where: no such page" }
        require(p.x in 0 until columns && p.y in 0 until rows) { "$where: off the grid" }
        require(listOfNotNull(p.app, p.folder, p.drawerFolder).size == 1) {
            "$where: must be exactly one of an app, a folder or a drawer folder"
        }
        p.drawerFolder?.let {
            require(it in drawerFolders.indices) { "$where: no such drawer folder" }
        }
        require(cells.add(listOf(p.container.ordinal, p.page, p.x, p.y))) {
            "$where: two things in one cell"
        }
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
/**
 * What a restore came to: [placed] placements written, [skipped] apps or placements left out, and
 * [widgetsKept] widgets already on the device left as they were (a backup carries none).
 */
data class Restored(val placed: Int, val skipped: Int, val widgetsKept: Int = 0)
