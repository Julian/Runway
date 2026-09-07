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
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DropAreaTracker
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
                val drawerOpen by viewModel.drawerOpen.collectAsStateWithLifecycle()
                val itemMenu by viewModel.itemMenu.state.collectAsStateWithLifecycle()
                var settleTarget by remember(settling) { mutableStateOf<Point?>(null) }
                val state = remember(base, pending) { base.applying(pending) }
                val reports = AreaReports(viewModel.dragging.areas) { state.settings }
                HomeScreen(
                    state = state,
                    goHome = viewModel.goHome,
                    flipHomePage = viewModel.dragging.flipHomePage,
                    flipDockPage = viewModel.dragging.flipDockPage,
                    onLaunch = viewModel::launch,
                    onSearch = viewModel::search,
                    itemMenu = itemMenu,
                    itemMenuActions = viewModel.itemMenu.actions,
                    onDismissItemMenu = viewModel.itemMenu::dismiss,
                    drawerOpen = drawerOpen,
                    onOpenDrawer = viewModel::openDrawer,
                    onCloseDrawer = viewModel::closeDrawer,
                    onLaunchApp = viewModel::launch,
                    drag =
                        DragSession(
                            state = drag,
                            settling = settling,
                            settleTarget = settleTarget,
                            onSettleTargetPositioned = { settleTarget = it },
                            onSettled = viewModel.dragging::settled,
                            onHold = viewModel.itemMenu::hold,
                            onStart = viewModel::startDrag,
                            onStartFromDrawer = viewModel::startDragFromDrawer,
                            onMove = viewModel.dragging::dragTo,
                            onEnd = viewModel.dragging::endDrag,
                            onCancel = viewModel.dragging::cancelDrag,
                        ),
                    onHomePagePositioned = reports::homePagePositioned,
                    onHomePageShown = reports::homePageShown,
                    onDockPagePositioned = reports::dockPagePositioned,
                    onDockPageShown = reports::dockPageShown,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.onHomeIntent()
    }
}

/** Forwards where the UI laid out its pages, with the grid dimensions of the moment. */
private class AreaReports(
    private val areas: DropAreaTracker,
    private val settings: () -> Settings,
) {
    fun homePagePositioned(page: Int, bounds: Bounds) =
        settings().let { areas.homePagePositioned(page, bounds, it.columns, it.pageRows) }

    fun homePageShown(page: Int) =
        settings().let { areas.homePageShown(page, it.columns, it.pageRows) }

    fun dockPagePositioned(page: Int, bounds: Bounds) =
        areas.dockPagePositioned(page, bounds, settings().dockSlots)

    fun dockPageShown(page: Int) = areas.dockPageShown(page, settings().dockSlots)
}
