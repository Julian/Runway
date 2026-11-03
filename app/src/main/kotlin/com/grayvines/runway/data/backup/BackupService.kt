package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.settings.SettingsRepository
import com.grayvines.runway.system.widgets.WidgetHost
import kotlinx.coroutines.flow.first

/** Makes a backup of the launcher as it is, and makes the launcher into one. */
class BackupService(
    private val workspace: WorkspaceRepository,
    private val settings: SettingsRepository,
    private val widgets: WidgetHost,
) {
    suspend fun export(): String =
        Backup(settings = settings.settings.first(), layout = workspace.layoutBackup()).toJson()

    /**
     * Replaces the settings and the layout with those in [text], keeping only the apps in
     * [installed] and those of a [quiet] profile (one that exists but reports no apps: paused).
     * Throws [IllegalArgumentException] for a file this cannot read, before touching anything.
     */
    suspend fun restore(
        text: String,
        installed: Set<AppRef>,
        quiet: Set<Long> = emptySet(),
    ): Restored {
        val backup = Backup.fromJson(text)
        val restored =
            workspace.restoreLayout(
                backup.layout,
                installed,
                quiet,
                bindWidget = widgets::restore,
                deleteWidget = widgets::deleteId,
            )
        settings.update { backup.settings }
        return restored
    }
}
