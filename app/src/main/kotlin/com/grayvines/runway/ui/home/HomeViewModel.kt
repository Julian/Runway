package com.grayvines.runway.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.Dropped
import com.grayvines.runway.data.FolderContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.foldInto
import com.grayvines.runway.data.observeDrawerPlacements
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.data.renameFolder
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.unfold
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.search.SearchTarget
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DragCoordinator
import com.grayvines.runway.ui.drag.DragSource
import com.grayvines.runway.ui.drag.DragWorkspace
import com.grayvines.runway.ui.drag.PendingMove
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.WorkspaceLookup
import com.grayvines.runway.ui.drawer.matching
import com.grayvines.runway.ui.folder.FolderActions
import com.grayvines.runway.ui.menu.ItemMenuHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Every placed item on every page, home and dock. */
fun HomeState.allItems(): List<HomeItem> =
    (homePages + dockPages).flatMap { it.items } + drawerFolders

/** A folder placement that is open, and the cell it opened out of. */
data class OpenFolder(val itemId: Long, val from: Bounds)

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
    /** For a folder: the apps in it that are available, in order. */
    val folder: List<AppEntry> = emptyList(),
    val folderId: Long? = null,
    /** A drawer folder: it lives in the drawer, and its apps stay out of the drawer's grid. */
    val inDrawer: Boolean = false,
) {
    val footprint: Footprint
        get() = Footprint(x, y, spanX, spanY)
}

/**
 * One page: [items] are drawn; [occupied] is every cell taken, including by items that are not
 * drawn right now (their app is unavailable), which the drag planner must respect.
 */
data class HomePage(
    val index: Int,
    val items: List<HomeItem>,
    val occupied: List<Placed> = items.map { Placed(it.id, it.footprint) },
)

data class HomeState(
    val settings: Settings = Settings(),
    val homePages: List<HomePage> = emptyList(),
    val dockPages: List<HomePage> = emptyList(),
    val searchTarget: SearchTarget? = null,
    /** Every launchable app, alphabetically: what the drawer shows and searches. */
    val apps: List<AppEntry> = emptyList(),
    /** The folders atop the drawer, by name. */
    val drawerFolders: List<HomeItem> = emptyList(),
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

    /** The folder open over the pages, and the cell (root px) it grew out of; null when none. */
    private val _openFolder = MutableStateFlow<OpenFolder?>(null)
    val openFolder: StateFlow<OpenFolder?> = _openFolder

    /** What is typed in the drawer's search field; every open starts empty. */
    private val _drawerQuery = MutableStateFlow("")
    val drawerQuery: StateFlow<String> = _drawerQuery

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
                firstOrNull { it.index == page }?.occupied ?: emptyList()
        }

    /** Persistence for drags; failures are logged and reported, never thrown. */
    private val dragWorkspace =
        object : DragWorkspace {
            override fun pageCount(container: Container) = state.value.pages(container).size

            override suspend fun move(move: PendingMove): Boolean =
                logged("could not save the move; the item snaps back") {
                    with(move) {
                        val app = newApp
                        val into = foldInto
                        val outOf = fromFolder
                        when {
                            into != null ->
                                check(
                                    graph.workspace.foldInto(
                                        into,
                                        if (app != null) Dropped.App(app) else Dropped.Item(itemId),
                                        outOf = outOf,
                                    )
                                ) {
                                    "nothing to fold: the target or the dropped app is gone"
                                }
                            app != null && outOf != null ->
                                graph.workspace.unfold(outOf, app, container, page, x, y)
                            app != null -> graph.workspace.addApp(app, container, page, x, y)
                            else ->
                                graph.workspace.moveItem(itemId, container, page, x, y, displaced)
                        }
                    }
                }

            // The state the screen draws, not the database flow directly: the override must
            // outlive the write until what replaces it is on screen, or the icon shows in its old
            // cell for a frame between the two.
            override suspend fun awaitReflected(move: PendingMove) {
                // Bounded: a move the layout never shows (the item vanished meanwhile) must not
                // keep the override, and the dropped icon hidden, for good.
                withTimeoutOrNull(REFLECT_TIMEOUT_MS) { state.first { it.reflects(move) } }
                    ?: Log.w(TAG, "the layout never showed $move")
            }

            override suspend fun addPage(container: Container, index: Int): Boolean =
                logged("could not add a page") {
                    graph.workspace.addPage(container, index)
                    state.first { it.pages(container).size > index }
                }

            override suspend fun pruneEmptyPages() {
                logged("could not remove empty pages") { graph.workspace.pruneEmptyPages() }
            }

            // Anything at all: a save that fails must snap the icon back, never take the
            // launcher down with it.
            @Suppress("TooGenericExceptionCaught")
            private suspend fun logged(what: String, block: suspend () -> Unit): Boolean =
                try {
                    block()
                    true
                } catch (e: Exception) {
                    Log.e(TAG, what, e)
                    false
                }
        }

    val dragging = DragCoordinator(viewModelScope, lookup, dragWorkspace)

    /**
     * Asking the package manager who handles a web search is slow: done only when the chosen
     * package or the installed apps change, not on every layout write.
     */
    private val searchTarget =
        combine(
                graph.settings.settings.map { it.searchTarget }.distinctUntilChanged(),
                graph.appRepository.apps,
            ) { chosen, _ ->
                graph.searchTargets.resolve(chosen)
            }
            .distinctUntilChanged()

    val state: StateFlow<HomeState> =
        combine(
                graph.settings.settings,
                graph.workspace.observe(Container.HOME),
                graph.workspace.observe(Container.DOCK),
                graph.appRepository.apps,
                graph.workspace.observeFolders(),
                searchTarget,
                graph.workspace.observeDrawerPlacements(),
            ) { flows ->
                @Suppress("UNCHECKED_CAST") val settings = flows[0] as Settings
                @Suppress("UNCHECKED_CAST") val home = flows[1] as ContainerContent
                @Suppress("UNCHECKED_CAST") val dock = flows[2] as ContainerContent
                @Suppress("UNCHECKED_CAST") val apps = flows[3] as List<AppEntry>
                @Suppress("UNCHECKED_CAST") val folders = flows[4] as List<FolderContent>
                val target = flows[5] as SearchTarget?
                @Suppress("UNCHECKED_CAST") val drawer = flows[6] as List<ItemEntity>
                val byKey = apps.associateBy { it.key }
                val byFolder = folders.associateBy { it.id }
                HomeState(
                    settings = settings,
                    homePages = home.toHomePages(byKey, byFolder),
                    dockPages = dock.toHomePages(byKey, byFolder),
                    drawerFolders = drawer.toDrawerFolders(byKey, byFolder),
                    searchTarget = target,
                    apps = apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }),
                    // Not before the app list: with it empty every icon would be hidden and
                    // every cell would look free.
                    loaded = apps.isNotEmpty(),
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState())

    /** What an open folder's sheet can do. Renaming applies to whichever folder is open. */
    val folderActions =
        FolderActions(
            launch = { app -> launch(app) },
            close = ::closeFolder,
            rename = { name ->
                val folderId = _openFolder.value?.let { state.value.item(it.itemId)?.folderId }
                if (folderId != null) {
                    viewModelScope.launch { graph.workspace.renameFolder(folderId, name) }
                }
            },
        )

    init {
        viewModelScope.launch { graph.workspace.ensureInitialised() }
        // A folder whose placement goes (uninstalled away, removed) is no longer open.
        viewModelScope.launch {
            state.collect { s ->
                val open = _openFolder.value
                if (open != null && s.loaded && s.allItems().none { it.id == open.itemId }) {
                    closeFolder()
                }
            }
        }
    }

    fun startDrag(item: HomeItem, container: Container, page: Int, pointer: Point, grab: Point) {
        itemMenu.dismiss()
        val source =
            DragSource(item.id, item.kind, container, page, item.x, item.y, item.spanX, item.spanY)
        dragging.startDrag(source, pointer, grab)
    }

    /**
     * An app pulled out of the drawer, or out of the open folder [fromFolder]: whichever was open
     * closes under it, and the drop places it.
     */
    fun startDragOfApp(app: AppEntry, fromFolder: Long?, pointer: Point, grab: Point) {
        itemMenu.dismiss()
        closeDrawer()
        closeFolder()
        val source =
            DragSource(
                0,
                ItemKind.APP,
                Container.DRAWER,
                0,
                0,
                0,
                newApp = app.ref,
                fromFolder = fromFolder,
            )
        dragging.startDrag(source, pointer, grab)
    }

    /** A tap on a placed item in [cell]: an app launches, a folder opens out of its cell. */
    fun launch(item: HomeItem, cell: Bounds) {
        when {
            item.app != null -> graph.appRepository.launch(item.app)
            item.kind == ItemKind.FOLDER -> _openFolder.value = OpenFolder(item.id, cell)
        }
    }

    fun closeFolder() {
        _openFolder.value = null
    }

    /** The search bar was tapped: hand off to the target app. */
    fun search() {
        graph.searchTargets.search(state.value.searchTarget)
    }

    /** Launches from the drawer or an open folder; whichever was open closes behind the app. */
    fun launch(app: AppEntry) {
        graph.appRepository.launch(app)
        closeDrawer()
        closeFolder()
    }

    fun openDrawer() {
        _drawerQuery.value = ""
        _drawerOpen.value = true
    }

    fun closeDrawer() {
        _drawerOpen.value = false
        _drawerQuery.value = ""
    }

    fun setDrawerQuery(query: String) {
        _drawerQuery.value = query
    }

    /** Enter in the drawer's search field launches the best match, if there is one. */
    fun launchDrawerMatch() {
        state.value.apps.matching(_drawerQuery.value).firstOrNull()?.let(::launch)
    }

    /** HOME closes whatever is open over the pages; with nothing open it returns to page 1. */
    fun onHomeIntent() {
        when {
            itemMenu.isOpen -> itemMenu.dismiss()
            _openFolder.value != null -> closeFolder()
            _drawerOpen.value -> closeDrawer()
            else -> _goHome.tryEmit(Unit)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val REFLECT_TIMEOUT_MS = 3_000L
        const val TAG = "Runway"
    }
}

/**
 * True once the drawn layout shows [move] applied: the item, or the new app, is in its cell; for a
 * fold, the app is in the folder there, or the placement it came from is gone.
 */
internal fun HomeState.reflects(move: PendingMove): Boolean {
    val newApp = move.newApp
    if (move.foldInto != null && newApp == null) {
        return allItems().none { it.id == move.itemId }
    }
    return pages(move.container).any { p ->
        p.index == move.page &&
            p.items.any { it.x == move.x && it.y == move.y && it.isMoverOf(move) }
    }
}

private fun HomeItem.isMoverOf(move: PendingMove): Boolean {
    val newApp = move.newApp
    return when {
        newApp != null && move.foldInto != null -> folder.any { it.ref == newApp }
        newApp != null -> app?.ref == newApp
        else -> id == move.itemId
    }
}
