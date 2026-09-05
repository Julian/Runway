package com.grayvines.runway

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grayvines.runway.ui.home.TemporaryAppGrid
import com.grayvines.runway.ui.theme.RunwayTheme

/** The HOME activity. Holds no state of its own. */
class LauncherActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val apps = appGraph.appRepository
        setContent {
            RunwayTheme {
                val list by apps.apps.collectAsStateWithLifecycle()
                TemporaryAppGrid(apps = list, onLaunch = { apps.launch(it) })
            }
        }
    }
}
