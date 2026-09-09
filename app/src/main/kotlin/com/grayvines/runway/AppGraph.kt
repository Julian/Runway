package com.grayvines.runway

import android.content.Context
import android.util.Log
import com.grayvines.runway.data.RunwayDatabase
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.backup.BackupService
import com.grayvines.runway.data.settings.SettingsRepository
import com.grayvines.runway.system.NotificationShade
import com.grayvines.runway.system.apps.AppRepository
import com.grayvines.runway.system.search.SearchTargetResolver
import com.grayvines.runway.system.wallpaper.WallpaperPicker
import com.grayvines.runway.ui.attempt
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** Application-wide singletons. */
class AppGraph(private val context: Context) {
    /** Background work (settings writes, layout writes). A failure is logged, never fatal. */
    val appScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, e -> Log.e(TAG, "background work failed", e) }
        )

    val appRepository: AppRepository by lazy { AppRepository(context, appScope) }
    val database: RunwayDatabase by lazy { RunwayDatabase.open(context) }
    val workspace: WorkspaceRepository by lazy { WorkspaceRepository(database) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val searchTargets: SearchTargetResolver by lazy { SearchTargetResolver(context) }
    val backup: BackupService by lazy { BackupService(workspace, settings) }
    val wallpapers: WallpaperPicker by lazy { WallpaperPicker(context) }
    val notificationShade: NotificationShade by lazy { NotificationShade(context) }

    /** Wiring that must run for the process lifetime. Called once from [RunwayApp]. */
    fun start() {
        // Every refresh also reconciles the layout, for uninstalls missed while not running.
        appScope.launch {
            appRepository.refreshed
                .filter { it.isNotEmpty() }
                .collect { apps ->
                    attempt("reconcile the layout with the installed apps") {
                        workspace.retainApps(apps.mapTo(mutableSetOf()) { it.ref })
                    }
                }
        }
    }
}

private const val TAG = "Runway"

val Context.appGraph: AppGraph
    get() = (applicationContext as RunwayApp).graph
