package com.grayvines.runway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.grayvines.runway.ui.home.HomeScreen
import com.grayvines.runway.ui.home.HomeViewModel
import com.grayvines.runway.ui.theme.RunwayTheme

/** The HOME activity. Holds no state of its own. */
class LauncherActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory { initializer { HomeViewModel(appGraph) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RunwayTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                HomeScreen(state = state, goHome = viewModel.goHome, onLaunch = viewModel::launch)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.onHomeIntent()
    }
}
