package com.grayvines.runway.ui.settings

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grayvines.runway.appGraph
import com.grayvines.runway.data.autoFill
import com.grayvines.runway.data.clear
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.theme.SettingsTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val graph = appGraph
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            SettingsTheme {
                val settings by graph.settings.settings.collectAsStateWithLifecycle(Settings())
                SettingsScreen(
                    settings = settings,
                    onChange = { transform ->
                        graph.appScope.launch { graph.settings.update(transform) }
                    },
                    debugActions =
                        if (debuggable) {
                            DebugActions(
                                fillWithAllApps = {
                                    graph.appScope.launch {
                                        val apps = graph.appRepository.apps.first().map { it.ref }
                                        val s = graph.settings.settings.first()
                                        graph.workspace.autoFill(
                                            apps,
                                            s.columns,
                                            s.pageRows,
                                            s.dockSlots,
                                        )
                                    }
                                },
                                clearLayout = { graph.appScope.launch { graph.workspace.clear() } },
                            )
                        } else {
                            null
                        },
                )
            }
        }
    }
}
