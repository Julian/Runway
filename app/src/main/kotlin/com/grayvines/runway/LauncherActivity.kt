package com.grayvines.runway

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DropAreaTracker
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drawer.DrawerQuery
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.DrawerActions
import com.grayvines.runway.ui.home.HomeScreen
import com.grayvines.runway.ui.home.HomeViewModel
import com.grayvines.runway.ui.home.applying
import com.grayvines.runway.ui.home.hasWidgetAt
import com.grayvines.runway.ui.menu.HomeMenuSession
import com.grayvines.runway.ui.menu.ItemMenuSession
import com.grayvines.runway.ui.settings.SettingsActivity
import com.grayvines.runway.ui.theme.RunwayTheme
import com.grayvines.runway.ui.widgets.WidgetPickerSession
import kotlinx.coroutines.flow.Flow

/** The HOME activity. Holds no state of its own. */
class LauncherActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels {
        viewModelFactory { initializer { HomeViewModel(appGraph) } }
    }

    private val drawerActions by lazy {
        DrawerActions(
            open = viewModel::openDrawer,
            close = viewModel::closeDrawer,
            openShade = ::openNotifications,
        )
    }

    private val homeMenuActions by lazy {
        viewModel.homeMenu.actions(
            openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            openWidgets = viewModel.widgetPicker::open,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Registered before the content, so every handler the content adds (the drawer's, a
        // folder's, a menu's) is asked first; this one is what is left when none of those is up.
        onBackPressedDispatcher.addCallback(this) { viewModel.onBack() }
        setContent {
            RunwayTheme {
                val base by viewModel.state.collectAsStateWithLifecycle()
                val pending by viewModel.dragging.pending.collectAsStateWithLifecycle()
                val drawerOpen by viewModel.drawerOpen.collectAsStateWithLifecycle()
                val itemMenu by viewModel.itemMenu.state.collectAsStateWithLifecycle()
                val openFolder by viewModel.openFolder.collectAsStateWithLifecycle()
                val homeMenuAt by viewModel.homeMenu.state.collectAsStateWithLifecycle()
                Notices(viewModel.widgetPicker.notices)
                val state = remember(base, pending) { base.applying(pending) }
                // The live settings, not this composition's: page-shown callbacks are kept by
                // effects that outlive it, and a changed grid must reach them.
                val reports =
                    AreaReports(viewModel.dragging.areas) { viewModel.state.value.settings }
                HomeScreen(
                    state = state,
                    goHome = viewModel.goHome,
                    flipHomePage = viewModel.dragging.flipHomePage,
                    flipDockPage = viewModel.dragging.flipDockPage,
                    onLaunch = viewModel::launch,
                    onSearch = viewModel::search,
                    itemMenu =
                        ItemMenuSession(
                            itemMenu,
                            viewModel.itemMenu.actions,
                            viewModel.itemMenu::dismiss,
                        ),
                    widgetPicker = widgetPickerSession(),
                    homeMenu =
                        HomeMenuSession(
                            at = homeMenuAt,
                            actions = homeMenuActions,
                            onOpen = viewModel.homeMenu::open,
                            onDismiss = viewModel.homeMenu::dismiss,
                            showPage = viewModel.homeMenu.showPage,
                        ),
                    openFolder = openFolder,
                    folderActions = viewModel.folderActions,
                    drawerOpen = drawerOpen,
                    drawerActions = drawerActions,
                    startsOnWidget = { p ->
                        viewModel.state.value.hasWidgetAt(viewModel.dragging.areas.areas, p)
                    },
                    drawerQuery = drawerQuery(),
                    onLaunchApp = viewModel::launch,
                    drag = rememberDragSession(),
                    onHomePagePositioned = reports::homePagePositioned,
                    onHomePageShown = reports::homePageShown,
                    onDockPagePositioned = reports::dockPagePositioned,
                    onDockPageShown = reports::dockPageShown,
                )
            }
        }
    }

    /** Widgets update only while the launcher is on screen. */
    override fun onStart() {
        super.onStart()
        appGraph.widgets.startListening()
    }

    override fun onStop() {
        appGraph.widgets.stopListening()
        super.onStop()
    }

    @Composable
    private fun widgetPickerSession(): WidgetPickerSession {
        val open by viewModel.widgetPicker.open.collectAsStateWithLifecycle()
        val providers by viewModel.widgetPicker.providers.collectAsStateWithLifecycle()
        return WidgetPickerSession(
            open = open,
            providers = providers,
            onPick = viewModel.widgetPicker::pick,
            onDismiss = viewModel.widgetPicker::dismiss,
            spanFor = viewModel.widgetPicker::spanFor,
        )
    }

    /** What could not be done, said in a toast. */
    @Composable
    private fun Notices(notices: Flow<String>) {
        LaunchedEffect(notices) {
            notices.collect { Toast.makeText(this@LauncherActivity, it, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        viewModel.onHomeIntent()
    }

    @Composable
    private fun drawerQuery(): DrawerQuery {
        return DrawerQuery(
            viewModel.drawerQuery,
            { viewModel.drawerQuery = it },
            viewModel::launchDrawerMatch,
        )
    }

    /** The one drag session, reading the live drag; nothing here changes while a finger moves. */
    @Composable
    private fun rememberDragSession(): DragSession {
        val drag = viewModel.dragging.drag.collectAsStateWithLifecycle()
        val settling = viewModel.dragging.settling.collectAsStateWithLifecycle()
        // A settle target is per drop: it forgets itself when the settling item changes.
        val settleTarget = remember(settling.value) { mutableStateOf<Point?>(null) }
        // A carried widget's picture outlives the session, which is remade per drop: the settle
        // after the drop still draws it.
        val picture = remember { mutableStateOf<ImageBitmap?>(null) }
        return remember(settleTarget) {
            DragSession(
                stateOf = drag,
                settlingOf = settling,
                settleTargetOf = settleTarget,
                pictureOf = picture,
                onSettleTargetPositioned = { settleTarget.value = it },
                onSettled = viewModel.dragging::settled,
                onHold = viewModel.itemMenu::hold,
                onStart = viewModel::startDrag,
                onStartNew = viewModel::startNewDrag,
                onMove = viewModel.dragging::dragTo,
                onEnd = viewModel.dragging::endDrag,
                onCancel = viewModel.dragging::cancelDrag,
            )
        }
    }

    /** A swipe down on the home screen. Some Androids refuse; then the user hears why. */
    private fun openNotifications() {
        if (!appGraph.notificationShade.open()) {
            Toast.makeText(this, "Android refused to open the notifications", Toast.LENGTH_SHORT)
                .show()
        }
    }
}

/** Forwards where the UI laid out its pages, with the grid dimensions of the moment. */
private class AreaReports(
    private val areas: DropAreaTracker,
    private val settings: () -> Settings,
) {
    fun homePagePositioned(page: Int, bounds: Bounds) =
        settings().let { areas.homePagePositioned(page, bounds, it.columns, it.pageRows) }

    fun homePageShown(page: Int, settled: Boolean) =
        settings().let { areas.homePageShown(page, it.columns, it.pageRows, settled) }

    fun dockPagePositioned(page: Int, bounds: Bounds) =
        areas.dockPagePositioned(page, bounds, settings().dockSlots)

    fun dockPageShown(page: Int, settled: Boolean) =
        areas.dockPageShown(page, settings().dockSlots, settled)
}
