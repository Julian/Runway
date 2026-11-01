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
 * placements of apps, folders and widgets. Shortcuts are not carried: their ids belong to the
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
 * placement that is not exactly one of an app, a folder, a drawer folder or a widget, a widget off
 * the home screen or with no provider, a drawer folder index there is no folder for, or a hidden
 * app in a drawer folder. Checked whole before anything is written, so a bad file changes nothing.
 */
internal fun Layout.validate(settings: Settings) {
    require(homePages >= 0 && dockPages >= 0) { "negative page counts" }
    val cells = mutableSetOf<List<Int>>()
    placements.forEachIndexed { i, p -> p.validate(i + 1, this, settings, cells) }
    val hidden = hiddenApps.orEmpty().toSet()
    drawerFolders.forEach { folder ->
        folder.apps
            .firstOrNull { it in hidden }
            ?.let {
                throw IllegalArgumentException(
                    "drawer folder ${folder.name}: ${it.component} is hidden from the drawer"
                )
            }
    }
}

private fun Placement.validate(
    number: Int,
    layout: Layout,
    settings: Settings,
    cells: MutableSet<List<Int>>,
) {
    val where = "placement $number ($container page $page, cell $x,$y)"
    val pages = if (container == Container.DOCK) layout.dockPages else layout.homePages
    val (columns, rows) =
        when (container) {
            Container.HOME -> settings.columns to settings.pageRows
            Container.DOCK -> settings.dockSlots to 1
            Container.DRAWER -> throw IllegalArgumentException("$where: the drawer has no cells")
        }
    require(page in 0 until pages) { "$where: no such page" }
    require(listOfNotNull(app, folder, drawerFolder, widget).size == 1) {
        "$where: must be exactly one of an app, a folder, a drawer folder or a widget"
    }
    drawerFolder?.let {
        require(it in layout.drawerFolders.indices) { "$where: no such drawer folder" }
    }
    widget?.validate(where, container)
    val spanX = widget?.spanX ?: 1
    val spanY = widget?.spanY ?: 1
    require(x in 0..columns - spanX && y in 0..rows - spanY) { "$where: off the grid" }
    for (cellY in y until y + spanY) {
        for (cellX in x until x + spanX) {
            require(cells.add(listOf(container.ordinal, page, cellX, cellY))) {
                "$where: two things in one cell"
            }
        }
    }
}

private fun Widget.validate(where: String, container: Container) {
    require(container == Container.HOME) { "$where: widgets live on the home screen only" }
    require(provider.isNotBlank() && '/' in provider) { "$where: widget has no provider" }
    require(spanX >= 1 && spanY >= 1) { "$where: widget span must be at least 1x1" }
}

/** The pages of each container and what sits on them. */
@Serializable
data class Layout(
    @SerialName("home_pages") val homePages: Int,
    @SerialName("dock_pages") val dockPages: Int,
    val placements: List<Placement>,
    /** The folders atop the drawer, which have no cell. */
    @SerialName("drawer_folders") val drawerFolders: List<Folder> = emptyList(),
    /**
     * The apps the drawer leaves out. Null in a file from before Runway could hide apps: a restore
     * of one leaves the device's hidden apps as they are.
     */
    @SerialName("hidden_apps") val hiddenApps: List<AppRef>? = null,
    /**
     * The apps whose notification badges are muted. Null in a file from before Runway could mute
     * badges: a restore of one leaves the device's muted apps as they are.
     */
    @SerialName("badge_mutes") val badgeMutes: List<AppRef>? = null,
)

/**
 * One cell's content: an [app], a [folder] of apps of its own, [drawerFolder], an index into
 * [Layout.drawerFolders], for a drawer folder placed in a cell as well, or a [widget] spanning
 * cells from this one.
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
    val widget: Widget? = null,
)

/** A widget as a backup carries it: its provider component name and the cells it spans. */
@Serializable
data class Widget(
    val provider: String,
    @SerialName("span_x") val spanX: Int = 1,
    @SerialName("span_y") val spanY: Int = 1,
)

/**
 * A folder as a backup carries it. [handSorted] says [apps] are in an order a hand put them in, one
 * a restore keeps; without it the list is only what the folder holds, shown as the drawer orders
 * it.
 */
@Serializable
data class Folder(
    val name: String,
    val apps: List<AppRef>,
    @SerialName("hand_sorted") val handSorted: Boolean = false,
)

/** How a restore went: the placements made, and the apps left out for not being installed. */
data class Restored(val placed: Int, val skipped: Int)
