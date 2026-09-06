package com.grayvines.runway.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.sqlite.SQLiteException
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.search.SearchTarget
import com.grayvines.runway.ui.drag.DragCoordinator
import com.grayvines.runway.ui.drag.DragSource
import com.grayvines.runway.ui.drag.DragWorkspace
import com.grayvines.runway.ui.drag.PendingMove
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.WorkspaceLookup
import com.grayvines.runway.ui.menu.ItemMenuHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One thing drawn in a cell. */
data class HomeItem(
    val id: Long,
    val kind: ItemKind,
    val x: Int,
    val y: Int,
    val spanX: Int,
    val spanY: Int,
    val label: String,
    val app: AppEntry?,
) {
    val footprint: Footprint
        get() = Footprint(x, y, spanX, spanY)
}

data class HomePage(val index: Int, val items: List<HomeItem>)

data class HomeState(
    val settings: Settings = Settings(),
    val homePages: List<HomePage> = emptyList(),
    val dockPages: List<HomePage> = emptyList(),
    val searchTarget: SearchTarget? = null,
    /** Every launchable app, alphabetically: what the drawer shows. */
    val apps: List<AppEntry> = emptyList(),
    val loaded: Boolean = false,
)

fun HomeState.pages(container: Container) =
    if (container == Container.DOCK) dockPages else homePages

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val _goHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Fires when the HOME intent arrives while already showing and nothing is open over the pages.
     */
    val goHome: SharedFlow<Unit> = _goHome

    private val _drawerOpen = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawerOpen

    /** The long-press item menu. */
    val itemMenu = ItemMenuHost(graph, viewModelScope)

    private val lookup =
        object : WorkspaceLookup {
            override val grid: GridSize
                get() = state.value.settings.let { GridSize(it.columns, it.pageRows) }

            override val dockSlots: Int
                get() = state.value.settings.dockSlots

            override fun homeItems(page: Int) = state.value.homePages.placed(page)

            override fun dockItems(page: Int) = state.value.dockPages.placed(page)

            private fun List<HomePage>.placed(page: Int) =
                firstOrNull { it.index == page }?.items?.map { Placed(it.id, it.footprint) }
                    ?: emptyList()
        }

    /** Persistence for drags; failures are logged and reported, never thrown. */
    private val dragWorkspace =
        object : DragWorkspace {
            override fun pageCount(container: Container) = state.value.pages(container).size

            override suspend fun move(move: PendingMove): Boolean =
                logged("could not save the move; the item snaps back") {
                    with(move) {
                        graph.workspace.moveItem(itemId, container, page, x, y, displaced)
                    }
                }

            override suspend fun awaitReflected(move: PendingMove) {
                graph.workspace.observe(move.container).first { it.reflects(move) }
            }

            override suspend fun addPage(container: Container, index: Int): Boolean =
                logged("could not add a page") {
                    graph.workspace.addPage(container, index)
                    state.first { it.pages(container).size > index }
                }

            override suspend fun pruneEmptyPages() {
                logged("could not remove empty pages") {
                    graph.workspace.pruneTrailingEmptyPages(Container.HOME)
                    graph.workspace.pruneTrailingEmptyPages(Container.DOCK)
                }
            }

            private suspend fun logged(what: String, block: suspend () -> Unit): Boolean =
                try {
                    block()
                    true
                } catch (e: SQLiteException) {
                    Log.e(TAG, what, e)
                    false
                }
        }

    val dragging = DragCoordinator(viewModelScope, lookup, dragWorkspace)

    val state: StateFlow<HomeState> =
        combine(
                graph.settings.settings,
                graph.workspace.observe(Container.HOME),
                graph.workspace.observe(Container.DOCK),
                graph.appRepository.apps,
            ) { settings, home, dock, apps ->
                val byKey = apps.associateBy { it.key }
                HomeState(
                    settings = settings,
                    homePages = home.toPages(byKey),
                    dockPages = dock.toPages(byKey),
                    searchTarget = graph.searchTargets.resolve(settings.searchTarget),
                    apps = apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
                    loaded = true,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState())

    init {
        viewModelScope.launch { graph.workspace.ensureInitialised() }
    }

    fun startDrag(item: HomeItem, container: Container, page: Int, pointer: Point, grab: Point) {
        itemMenu.dismiss()
        val source =
            DragSource(item.id, item.kind, container, page, item.x, item.y, item.spanX, item.spanY)
        dragging.startDrag(source, pointer, grab)
    }

    fun launch(item: HomeItem) {
        item.app?.let(graph.appRepository::launch)
    }

    /** The search bar was tapped: hand off to the target app. */
    fun search() {
        graph.searchTargets.search(state.value.searchTarget)
    }

    /** Launches from the drawer; the drawer closes behind the app. */
    fun launch(app: AppEntry) {
        graph.appRepository.launch(app)
        _drawerOpen.value = false
    }

    fun openDrawer() {
        _drawerOpen.value = true
    }

    fun closeDrawer() {
        _drawerOpen.value = false
    }

    /** HOME closes whatever is open over the pages; with nothing open it returns to page 1. */
    fun onHomeIntent() {
        when {
            itemMenu.isOpen -> itemMenu.dismiss()
            _drawerOpen.value -> _drawerOpen.value = false
            else -> _goHome.tryEmit(Unit)
        }
    }

    private fun ContainerContent.toPages(apps: Map<String, AppEntry>): List<HomePage> =
        pages.map { page ->
            HomePage(page.index, page.items.mapNotNull { it.toHomeItem(apps) })
        }

    /** Null when the item has no cell or its app is gone; those are not drawn. */
    private fun ItemEntity.toHomeItem(apps: Map<String, AppEntry>): HomeItem? {
        val cellX = x
        val cellY = y
        if (cellX == null || cellY == null) return null
        val app = apps["$profile/$component"]
        if (kind == ItemKind.APP && app == null) return null
        return HomeItem(
            id = id,
            kind = kind,
            x = cellX,
            y = cellY,
            spanX = spanX,
            spanY = spanY,
            label = labelOverride ?: app?.label ?: "",
            app = app,
        )
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TAG = "Runway"
    }
}

/** True once the observed layout shows [move] applied. */
internal fun ContainerContent.reflects(move: PendingMove) = pages.any { p ->
    p.index == move.page && p.items.any { it.id == move.itemId && it.x == move.x && it.y == move.y }
}
