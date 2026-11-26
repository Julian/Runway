package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.settings.BadgeStyle
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.data.settings.IconShape
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.settings.SwipeAction
import com.grayvines.runway.data.settings.clamped
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull

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
            val root = parseBackupRoot(format, text)
            checkBackupVersion(root)
            val sanitized = sanitizeBackupSettings(format, root)
            val backup =
                try {
                    format.decodeFromJsonElement<Backup>(sanitized)
                } catch (e: SerializationException) {
                    throw IllegalArgumentException(NOT_A_BACKUP, e)
                }
            val settings = backup.settings.clamped()
            backup.layout.validate()
            return backup.copy(settings = settings)
        }
    }
}

internal const val NOT_A_BACKUP = "not a Runway backup"
internal const val MAX_PAGES = 100
internal const val MAX_PLACEMENTS = 4_096
internal const val MAX_DRAWER_FOLDERS = 500
internal const val MAX_FOLDER_APPS = 500
internal const val MAX_APP_LIST = 5_000
internal const val MAX_FOLDER_NAME_LENGTH = 100

private val ICON_SHAPE_NAMES = IconShape.entries.mapTo(HashSet()) { it.name }
private val BADGE_STYLE_NAMES = BadgeStyle.entries.mapTo(HashSet()) { it.name }
private val DRAWER_SWIPE_NAMES = DrawerSwipe.entries.mapTo(HashSet()) { it.name }

internal fun parseBackupRoot(format: Json, text: String): JsonObject {
    val element =
        try {
            format.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(NOT_A_BACKUP, e)
        }
    return element as? JsonObject ?: throw IllegalArgumentException(NOT_A_BACKUP)
}

internal fun checkBackupVersion(root: JsonObject) {
    val versionElement = root["version"] ?: return
    val primitive = versionElement as? JsonPrimitive ?: throw IllegalArgumentException(NOT_A_BACKUP)
    val version =
        primitive.takeUnless { it.isString }?.intOrNull
            ?: throw IllegalArgumentException(NOT_A_BACKUP)
    require(version >= 1) { NOT_A_BACKUP }
    require(version <= Backup.VERSION) { "made by a newer Runway ($version)" }
}

/**
 * Strips settings values from a newer Runway (unknown enum names or [SwipeAction] subtypes) so they
 * fall back to defaults instead of rejecting an otherwise valid backup.
 */
internal fun sanitizeBackupSettings(format: Json, root: JsonObject): JsonObject {
    val rawSettings = root["settings"] as? JsonObject ?: return root
    val cleaned = LinkedHashMap<String, JsonElement>(rawSettings.size)
    rawSettings.forEach { (key, value) ->
        if (keepSettingField(format, key, value)) cleaned[key] = value
    }
    return JsonObject(root + ("settings" to JsonObject(cleaned)))
}

internal fun keepSettingField(format: Json, key: String, value: JsonElement): Boolean =
    when (key) {
        "iconShape" -> value.stringOrNull() in ICON_SHAPE_NAMES
        "badgeStyle" -> value.stringOrNull() in BADGE_STYLE_NAMES
        "drawerSwipe" -> value.stringOrNull() in DRAWER_SWIPE_NAMES
        "swipeRight" ->
            value is JsonNull ||
                try {
                    format.decodeFromJsonElement<SwipeAction>(value)
                    true
                } catch (_: IllegalArgumentException) {
                    false
                }
        else -> true
    }

internal fun JsonElement.stringOrNull(): String? =
    (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * Throws [IllegalArgumentException], naming the first fault, for a layout no restore should try:
 * structural faults checked by [validateStructure], plus a hidden app in a drawer folder or an app
 * appearing in more than one drawer folder. Checked whole before anything is written, so a bad file
 * changes nothing.
 */
internal fun Layout.validate() {
    validateStructure()
    val hidden = hiddenApps.orEmpty().toSet()
    val inDrawerFolders = mutableSetOf<AppRef>()
    drawerFolders.forEach { folder ->
        val inThisFolder = mutableSetOf<AppRef>()
        folder.apps.forEach { app ->
            require(app !in hidden) {
                "drawer folder ${folder.name}: ${app.component} is hidden from the drawer"
            }
            require(inThisFolder.add(app)) {
                "drawer folder ${folder.name}: duplicate ${app.component}"
            }
            require(inDrawerFolders.add(app)) {
                "drawer folder ${folder.name}: ${app.component} is in more than one drawer folder"
            }
        }
    }
}

/**
 * Structural checks every [Layout] must pass before writing to the database: valid page counts,
 * bounded collection sizes, valid component names and folder names, non-overlapping placements
 * within Runway's maximum grid bounds (so backups exported after shrinking the grid still restore),
 * valid widget providers/spans on the home screen only, and valid drawer folder indices.
 */
internal fun Layout.validateStructure() {
    require(homePages >= 0 && dockPages >= 0) { "negative page counts" }
    require(homePages <= MAX_PAGES && dockPages <= MAX_PAGES) { "too many pages" }
    require(placements.size <= MAX_PLACEMENTS) { "too many placements" }
    require(drawerFolders.size <= MAX_DRAWER_FOLDERS) { "too many drawer folders" }
    require(hiddenApps.orEmpty().size <= MAX_APP_LIST) { "too many hidden apps" }
    require(badgeMutes.orEmpty().size <= MAX_APP_LIST) { "too many muted apps" }
    drawerFolders.forEachIndexed { i, folder -> folder.validate("drawer folder ${i + 1}") }
    hiddenApps?.forEach { it.validate("hidden apps") }
    badgeMutes?.forEach { it.validate("badge mutes") }
    val cells = mutableSetOf<List<Int>>()
    placements.forEachIndexed { i, p -> p.validate(i + 1, this, cells) }
}

private fun Placement.validate(number: Int, layout: Layout, cells: MutableSet<List<Int>>) {
    val where = "placement $number ($container page $page, cell $x,$y)"
    val pages = if (container == Container.DOCK) layout.dockPages else layout.homePages
    val (columns, rows) =
        when (container) {
            Container.HOME -> Settings.MAX_COLUMNS to Settings.MAX_PAGE_ROWS
            Container.DOCK -> Settings.MAX_DOCK_SLOTS to 1
            Container.DRAWER -> throw IllegalArgumentException("$where: the drawer has no cells")
        }
    require(page in 0 until pages) { "$where: no such page" }
    require(listOfNotNull(app, folder, drawerFolder, widget).size == 1) {
        "$where: must be exactly one of an app, a folder, a drawer folder or a widget"
    }
    app?.validate(where)
    folder?.validate(where)
    drawerFolder?.let {
        require(it in layout.drawerFolders.indices) { "$where: no such drawer folder" }
    }
    widget?.validate(where, container)
    val spanX = widget?.spanX ?: 1
    val spanY = widget?.spanY ?: 1
    require(x in 0..columns - spanX && y in 0..rows - spanY) { "$where: off the grid" }
    recordCells(cells, container, page, x, y, spanX, spanY, where)
}

@Suppress("LongParameterList")
private fun recordCells(
    cells: MutableSet<List<Int>>,
    container: Container,
    page: Int,
    x: Int,
    y: Int,
    spanX: Int,
    spanY: Int,
    where: String,
) {
    for (cellY in y until y + spanY) {
        for (cellX in x until x + spanX) {
            require(cells.add(listOf(container.ordinal, page, cellX, cellY))) {
                "$where: two things in one cell"
            }
        }
    }
}

private fun Folder.validate(where: String) {
    require(name.isNotBlank()) { "$where: folder name must not be blank" }
    require(name.length <= MAX_FOLDER_NAME_LENGTH) { "$where: folder name is too long" }
    require(apps.size <= MAX_FOLDER_APPS) { "$where: too many apps in folder" }
    apps.forEach { it.validate(where) }
}

private fun AppRef.validate(where: String) {
    require(isValidComponent(component)) { "$where: invalid component" }
}

private fun Widget.validate(where: String, container: Container) {
    require(container == Container.HOME) { "$where: widgets live on the home screen only" }
    require(isValidComponent(provider)) { "$where: widget has no provider" }
    require(spanX >= 1 && spanY >= 1) { "$where: widget span must be at least 1x1" }
}

internal fun isValidComponent(value: String): Boolean {
    val slash = value.indexOf('/')
    if (slash <= 0 || slash >= value.lastIndex) return false
    val pkg = value.substring(0, slash)
    val cls = value.substring(slash + 1)
    return pkg.isNotBlank() && cls.isNotBlank() && '/' !in cls
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
