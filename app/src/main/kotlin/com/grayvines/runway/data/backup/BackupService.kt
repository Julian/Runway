package com.grayvines.runway.data.backup

import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.settings.SettingsRepository
import kotlinx.coroutines.flow.first

/** Makes a backup of the launcher as it is, and makes the launcher into one. */
class BackupService(
    private val workspace: WorkspaceRepository,
    private val settings: SettingsRepository,
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
        settings.update { backup.settings }
        return workspace.restoreLayout(backup.layout, installed, quiet)
    }
}
