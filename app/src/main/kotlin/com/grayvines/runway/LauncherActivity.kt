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
import com.grayvines.runway.ui.home.DragSession
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
                val drag by viewModel.drag.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    goHome = viewModel.goHome,
                    flipPage = viewModel.flipPage,
                    onLaunch = viewModel::launch,
                    drag =
                        DragSession(
                            state = drag,
                            onStart = viewModel::startDrag,
                            onMove = viewModel::dragTo,
                            onEnd = viewModel::endDrag,
                            onCancel = viewModel::cancelDrag,
                        ),
                    onHomePagePositioned = { page, bounds ->
                        val s = state.settings
                        viewModel.areas.homePagePositioned(page, bounds, s.columns, s.pageRows)
                    },
                    onHomePageShown = { page ->
                        val s = state.settings
                        viewModel.areas.homePageShown(page, s.columns, s.pageRows)
                    },
                    onDockPagePositioned = { page, bounds ->
                        viewModel.areas.dockPagePositioned(page, bounds, state.settings.dockSlots)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.onHomeIntent()
    }
}
