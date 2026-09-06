package com.grayvines.runway

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.HomeScreen
import com.grayvines.runway.ui.home.HomeViewModel
import com.grayvines.runway.ui.home.applying
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
                val base by viewModel.state.collectAsStateWithLifecycle()
                val drag by viewModel.dragging.drag.collectAsStateWithLifecycle()
                val pending by viewModel.dragging.pending.collectAsStateWithLifecycle()
                val settling by viewModel.dragging.settling.collectAsStateWithLifecycle()
                var settleTarget by remember(settling) { mutableStateOf<Point?>(null) }
                val state = remember(base, pending) { base.applying(pending) }
                HomeScreen(
                    state = state,
                    goHome = viewModel.goHome,
                    flipPage = viewModel.dragging.flipPage,
                    onLaunch = viewModel::launch,
                    drag =
                        DragSession(
                            state = drag,
                            settling = settling,
                            settleTarget = settleTarget,
                            onSettleTargetPositioned = { settleTarget = it },
                            onSettled = viewModel.dragging::settled,
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
