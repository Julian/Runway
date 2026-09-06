package com.grayvines.runway

import android.content.Context
import com.grayvines.runway.data.RunwayDatabase
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.settings.SettingsRepository
import com.grayvines.runway.system.apps.AppRepository
import com.grayvines.runway.system.search.SearchTargetResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Application-wide singletons. */
class AppGraph(private val context: Context) {
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val appRepository: AppRepository by lazy { AppRepository(context, appScope) }
    val database: RunwayDatabase by lazy { RunwayDatabase.open(context) }
    val workspace: WorkspaceRepository by lazy { WorkspaceRepository(database) }
    val settings: SettingsRepository by lazy { SettingsRepository(context) }
    val searchTargets: SearchTargetResolver by lazy { SearchTargetResolver(context) }

    /** Wiring that must run for the process lifetime. Called once from [RunwayApp]. */
    fun start() {
        appScope.launch {
            appRepository.removed.collect { workspace.removePackage(it.component, it.profile) }
        }
    }
}

val Context.appGraph: AppGraph
    get() = (applicationContext as RunwayApp).graph
