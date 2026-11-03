package com.grayvines.runway.system.apps

import android.content.pm.LauncherApps
import android.content.pm.LauncherApps.ShortcutQuery
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** The most shortcuts shown in an app's long-press menu: static first, then dynamic, by rank. */
internal const val MAX_SHORTCUTS = 4

/** One static or dynamic shortcut published by an app, ready for its long-press menu. */
data class AppShortcut(
    val id: String,
    val label: String,
    val icon: ImageBitmap? = null,
    val isDynamic: Boolean = false,
    val rank: Int = 0,
)

/**
 * Picks up to [MAX_SHORTCUTS] from [candidates]: static (manifest) shortcuts ahead of dynamic ones,
 * each group ordered by [AppShortcut.rank].
 */
internal fun selectShortcuts(candidates: List<AppShortcut>): List<AppShortcut> =
    candidates
        .sortedWith(compareBy<AppShortcut> { it.isDynamic }.thenBy { it.rank })
        .take(MAX_SHORTCUTS)

/**
 * Queries the static and dynamic shortcuts for [entry] from [LauncherApps]; empty when this
 * launcher is not the default home app (and so lacks shortcut-host permission) or the package has
 * none.
 */
internal fun LauncherApps.queryShortcuts(
    entry: AppEntry,
    densityDpi: Int,
    iconPx: Int,
): List<AppShortcut> {
    if (!hasShortcutHostPermission()) return emptyList()
    val query =
        ShortcutQuery()
            .setPackage(entry.component.packageName)
            .setActivity(entry.component)
            .setQueryFlags(ShortcutQuery.FLAG_MATCH_MANIFEST or ShortcutQuery.FLAG_MATCH_DYNAMIC)
    val raw =
        try {
            getShortcuts(query, entry.user).orEmpty()
        } catch (_: SecurityException) {
            return emptyList()
        } catch (_: IllegalStateException) {
            return emptyList()
        }
    val byId =
        raw.filter { it.isEnabled && (it.activity == null || it.activity == entry.component) }
            .associateBy { it.id }
    val candidates =
        byId.values.mapNotNull { info ->
            val label = (info.shortLabel ?: info.longLabel)?.toString()?.trim()
            if (label.isNullOrEmpty()) {
                null
            } else {
                AppShortcut(
                    id = info.id,
                    label = label,
                    isDynamic = info.isDynamic,
                    rank = info.rank,
                )
            }
        }
    return selectShortcuts(candidates).map { shortcut ->
        val info = byId.getValue(shortcut.id)
        val bitmap = runCatching {
            getShortcutIconDrawable(info, densityDpi)?.toBitmap(iconPx, iconPx)?.asImageBitmap()
        }
            .getOrNull()
        shortcut.copy(icon = bitmap)
    }
}
