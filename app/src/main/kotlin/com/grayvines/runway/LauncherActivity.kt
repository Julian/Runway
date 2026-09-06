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
                val drag by viewModel.dragging.drag.collectAsStateWithLifecycle()
                HomeScreen(
                    state = state,
                    goHome = viewModel.goHome,
                    flipPage = viewModel.dragging.flipPage,
                    onLaunch = viewModel::launch,
                    drag =
                        DragSession(
                            state = drag,
                            onStart = viewModel::startDrag,
                            onMove = viewModel.dragging::dragTo,
                            onEnd = viewModel.dragging::endDrag,
                            onCancel = viewModel.dragging::cancelDrag,
                        ),
                    onHomePagePositioned = { page, bounds ->
                        val s = state.settings
                        viewModel.dragging.areas.homePagePositioned(
                            page,
                            bounds,
                            s.columns,
                            s.pageRows,
                        )
                    },
                    onHomePageShown = { page ->
                        val s = state.settings
                        viewModel.dragging.areas.homePageShown(page, s.columns, s.pageRows)
                    },
                    onDockPagePositioned = { page, bounds ->
                        viewModel.dragging.areas.dockPagePositioned(
                            page,
                            bounds,
                            state.settings.dockSlots,
                        )
                    },
                    onDockPageShown = { page ->
                        viewModel.dragging.areas.dockPageShown(page, state.settings.dockSlots)
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
