package com.grayvines.runway

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.widgets.bindWidgetRequest
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DropAreaTracker
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.Settling
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
import com.grayvines.runway.ui.widgets.WidgetPrompts
import com.grayvines.runway.ui.widgets.rememberWidgetResizeSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The HOME activity. Holds no state of its own. */
class LauncherActivity : ComponentActivity() {
    // Internal for the instrumented tests, which wait on the drag coordinator's signals.
    internal val viewModel: HomeViewModel by viewModels {
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
            openSettings = {
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            },
            openWidgets = viewModel.widgetPicker::open,
        )
    }

    /** The system's leave to bind widgets, asked for once; its answer ends [pendingBind]. */
    private val bindRequest =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            pendingBind?.complete(result.resultCode == RESULT_OK)
            pendingBind = null
        }
    private var pendingBind: CompletableDeferred<Boolean>? = null
    private var pendingSetup: CompletableDeferred<Boolean>? = null

    /**
     * One prompt at a time: a second widget asked for while a prompt is up waits for that answer,
     * then asks its own, rather than overwriting the first's slot (which would leave the first
     * waiting for good and hand the second an answer meant for another id).
     */
    private val prompting = Mutex()

    /** The dialogs a widget may need before it is added; each answers when the user has. */
    private val widgetPrompts =
        object : WidgetPrompts {
            override suspend fun requestBind(
                id: Int,
                provider: ComponentName,
                profile: UserHandle,
            ): Boolean = prompting.withLock {
                val answer = CompletableDeferred<Boolean>()
                pendingBind = answer
                bindRequest.launch(bindWidgetRequest(id, provider, profile))
                answer.await()
            }

            override suspend fun configure(id: Int): Boolean = prompting.withLock {
                val answer = CompletableDeferred<Boolean>()
                pendingSetup = answer
                try {
                    appGraph.widgets.configure(this@LauncherActivity, id, REQUEST_WIDGET_SETUP)
                    answer.await()
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "no setup screen for widget $id", e)
                    pendingSetup = null
                    false
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        viewModel.widgetPicker.prompts = widgetPrompts
        viewModel.widgetResize.prompts = widgetPrompts
        // Registered before the content, so every handler the content adds (the drawer's, a
        // folder's, a menu's) is asked first; this one is what is left when none of those is up.
        onBackPressedDispatcher.addCallback(this) { viewModel.onBack() }
        setContent {
            RunwayTheme {
                val base by viewModel.state.collectAsStateWithLifecycle()
                val pending by viewModel.dragging.pending.collectAsStateWithLifecycle()
                val drawerOpen by viewModel.drawerOpen.collectAsStateWithLifecycle()
                val openFolder by viewModel.openFolder.collectAsStateWithLifecycle()
                Notices(viewModel.widgetPicker.notices)
                val state = remember(base, pending) { base.applying(pending) }
                // The live settings, not this composition's: page-shown callbacks are kept by
                // effects that outlive it, and a changed grid must reach them.
                val reports = remember {
                    AreaReports(viewModel.dragging.areas) { viewModel.state.value.settings }
                }
                HomeScreen(
                    state = state,
                    goHome = viewModel.goHome,
                    flipHomePage = viewModel.dragging.flipHomePage,
                    flipDockPage = viewModel.dragging.flipDockPage,
                    onLaunch = viewModel::launch,
                    onSearch = viewModel::search,
                    onSearchLongClick = viewModel::openSearchTarget,
                    onSwipePastFirstPage = viewModel.swipedPastFirstPage,
                    itemMenu = viewModel.itemMenuSession(),
                    widgetPicker = viewModel.widgetPickerSession(),
                    widgetResize = rememberWidgetResizeSession(viewModel.widgetResize),
                    homeMenu = homeMenuSession(),
                    openFolder = openFolder,
                    folderActions = viewModel.folderActions,
                    drawerOpen = drawerOpen,
                    drawerActions = drawerActions,
                    startsOnWidget = { p ->
                        viewModel.state.value
                            .applying(viewModel.dragging.pending.value)
                            .hasWidgetAt(viewModel.dragging.areas.areas, p)
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

    override fun onDestroy() {
        // The view model outlives this activity, and must not keep it through the prompts. A
        // prompt still up answers a later instance, which has no one waiting: the add is dropped.
        if (viewModel.widgetPicker.prompts === widgetPrompts) viewModel.widgetPicker.prompts = null
        if (viewModel.widgetResize.prompts === widgetPrompts) viewModel.widgetResize.prompts = null
        pendingBind?.complete(false)
        pendingSetup?.complete(false)
        super.onDestroy()
    }

    /** A widget's setup screen, started by the host, answers here; there is no newer way. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WIDGET_SETUP) {
            pendingSetup?.complete(resultCode == RESULT_OK)
            pendingSetup = null
        }
    }

    /** Widgets update only while the launcher is on screen. */
    override fun onStart() {
        super.onStart()
        appGraph.widgets.startListening()
        appGraph.badges.refresh()
    }

    override fun onStop() {
        appGraph.widgets.stopListening()
        super.onStop()
    }

    @Composable
    private fun homeMenuSession(): HomeMenuSession {
        val homeMenuAt by viewModel.homeMenu.state.collectAsStateWithLifecycle()
        return remember(homeMenuAt) {
            HomeMenuSession(
                at = homeMenuAt,
                actions = homeMenuActions,
                onOpen = viewModel.homeMenu::open,
                onDismiss = viewModel.homeMenu::dismiss,
                showPage = viewModel.homeMenu.showPage,
            )
        }
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
        val text = viewModel.drawerQuery
        return remember(text) {
            DrawerQuery(
                text,
                { viewModel.drawerQuery = it },
                viewModel::launchDrawerMatch,
            )
        }
    }

    /** The one drag session, reading the live drag; nothing here changes while a finger moves. */
    @Composable
    private fun rememberDragSession(): DragSession {
        val drag = viewModel.dragging.drag.collectAsStateWithLifecycle()
        val settling = viewModel.dragging.settling.collectAsStateWithLifecycle()
        // A settle target is per drop: exposed only while it belongs to the current settle.
        val targetFor = remember { mutableStateOf<Pair<Settling?, Point>?>(null) }
        val settleTarget = remember {
            derivedStateOf { targetFor.value?.takeIf { it.first == settling.value }?.second }
        }
        val active by remember { derivedStateOf { drag.value != null || settling.value != null } }
        val picture = remember { mutableStateOf<ImageBitmap?>(null) }
        if (!active) picture.value = null
        return remember {
            DragSession(
                stateOf = drag,
                settlingOf = settling,
                settleTargetOf = settleTarget,
                pictureOf = picture,
                onSettleTargetPositioned = { targetFor.value = settling.value to it },
                onSettled = viewModel.dragging::settled,
                onBinPositioned = viewModel.dragging.areas::binPositioned,
                onHold = viewModel.hold,
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

    private companion object {
        const val REQUEST_WIDGET_SETUP = 1
        const val TAG = "Runway"
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

@Composable
internal fun HomeViewModel.itemMenuSession(): ItemMenuSession {
    val open by itemMenu.state.collectAsStateWithLifecycle()
    return remember(this, open) { ItemMenuSession(open, itemMenu.actions, itemMenu::dismiss) }
}

@Composable
internal fun HomeViewModel.widgetPickerSession(): WidgetPickerSession {
    val open by widgetPicker.open.collectAsStateWithLifecycle()
    val providers by widgetPicker.providers.collectAsStateWithLifecycle()
    return remember(this, open, providers) {
        WidgetPickerSession(
            open = open,
            providers = providers,
            onPick = widgetPicker::pick,
            onDismiss = widgetPicker::dismiss,
            spanFor = widgetPicker::spanFor,
        )
    }
}
