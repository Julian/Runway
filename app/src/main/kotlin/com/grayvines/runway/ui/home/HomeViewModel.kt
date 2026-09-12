package com.grayvines.runway.ui.home

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.Dropped
import com.grayvines.runway.data.FolderContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.foldInto
import com.grayvines.runway.data.folderIdentity
import com.grayvines.runway.data.moveOutOf
import com.grayvines.runway.data.observeDrawerPlacements
import com.grayvines.runway.data.observeFolders
import com.grayvines.runway.data.placeFolder
import com.grayvines.runway.data.renameFolder
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.data.unfold
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.LabelOrder
import com.grayvines.runway.system.search.SearchTarget
import com.grayvines.runway.ui.attempt
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DragCoordinator
import com.grayvines.runway.ui.drag.DragSource
import com.grayvines.runway.ui.drag.DragWorkspace
import com.grayvines.runway.ui.drag.PendingMove
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.WorkspaceLookup
import com.grayvines.runway.ui.drawer.matching
import com.grayvines.runway.ui.folder.FolderActions
import com.grayvines.runway.ui.menu.HomeMenuHost
import com.grayvines.runway.ui.menu.ItemMenuHost
import com.grayvines.runway.ui.menu.ItemMenuState
import com.grayvines.runway.ui.widgets.WidgetPickerHost
import com.grayvines.runway.ui.widgets.WidgetResizeHost
import com.grayvines.runway.ui.writing
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
/**
 * The folder whose sheet is up, opened out of [from]; [leaving] once something was dragged out of
 * it, when the sheet slides away under the finger before it goes.
 */
data class OpenFolder(val itemId: Long, val from: Bounds, val leaving: Boolean = false)

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
    /**
     * A drawer folder, wherever this placement of it is: its apps stay out of the drawer's grid,
     * and are copied, not moved, when dragged out of it.
     */
    val inDrawer: Boolean = false,
    /** For a widget: the host's id for it, and the provider it is (or was) bound to. */
    val appWidgetId: Int? = null,
    val provider: String? = null,
) {
    val footprint: Footprint
        get() = Footprint(x, y, spanX, spanY)

    /** What this is a placement of; see [com.grayvines.runway.model.Placed.identity]. */
    val identity: String?
        get() = app?.ref?.identity ?: folderId?.let(::folderIdentity)
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

    /**
     * What is typed in the drawer's search field; every open starts empty. Snapshot state rather
     * than a flow: the field must see each keystroke's result before the next arrives, and a flow
     * collected into composition lands a frame late, which under fast typing loses characters.
     */
    var drawerQuery by mutableStateOf("")

    /** The long-press item menu. */
    // Neither menu opens over a live drag: a second finger's long press changes nothing.
    val itemMenu = ItemMenuHost(graph, viewModelScope, mayOpen = { dragging.drag.value == null })

    val homeMenu =
        HomeMenuHost(
            graph,
            viewModelScope,
            awaitPages = { count -> state.first { it.homePages.size >= count } },
            mayOpen = { dragging.drag.value == null },
        )

    /** The frame around a widget: its handles and its Remove. */
    val widgetResize =
        WidgetResizeHost(graph, viewModelScope, mayShow = { dragging.drag.value == null })

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

            override suspend fun move(move: PendingMove): Boolean {
                if (move.newWidget != null) return widgetPicker.place(move)
                val saved =
                    attempt("save the move; the item snaps back") {
                        check(graph.workspace.apply(move)) {
                            "the page, a neighbour, the target or the dropped app is gone since " +
                                "the plan was made; nothing saved"
                        }
                    }
                return saved
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
                attempt("add a page") {
                    graph.workspace.addPage(container, index)
                    state.first { it.pages(container).size > index }
                }

            override suspend fun pruneEmptyPages() {
                attempt("remove empty pages") { graph.workspace.pruneEmptyPages() }
            }
        }

    // Typed explicitly: the picker places widgets dropped by the coordinator, and the coordinator
    // asks the picker for the shown page, so inference would chase its own tail.
    val dragging: DragCoordinator = DragCoordinator(viewModelScope, lookup, dragWorkspace)

    /** The widget picker, adding to the page on screen. */
    val widgetPicker: WidgetPickerHost =
        WidgetPickerHost(
            graph,
            viewModelScope,
            lookup,
            shownPage = { dragging.areas.areas.homePage },
            mayOpen = { dragging.drag.value == null },
            onPlaced = { widgetResize.show(it) },
        )

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
                    apps = apps.sortedWith(compareBy(LabelOrder.comparator()) { it.label }),
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
            // By id, not "the open folder": a rename committed as the folder closes arrives
            // after it is no longer open, and must not be lost for that.
            rename = { folderId, name ->
                viewModelScope.writing("rename the folder") {
                    graph.workspace.renameFolder(folderId, name)
                }
            },
        )

    init {
        viewModelScope.writing("set up the layout") { graph.workspace.ensureInitialised() }
        // A folder whose placement goes (uninstalled away, removed) is no longer open; nor is a
        // menu whose item goes, which would otherwise keep offering actions on nothing.
        viewModelScope.launch {
            state.collect { s ->
                val open = _openFolder.value
                if (open != null && s.loaded && s.allItems().none { it.id == open.itemId }) {
                    closeFolder()
                }
                val menu = itemMenu.state.value
                if (menu != null && s.loaded && !s.stillHas(menu)) itemMenu.dismiss()
                val framed = widgetResize.shown.value
                if (framed != null && s.loaded && s.item(framed) == null) widgetResize.dismiss()
            }
        }
        // A placed widget's drag ends with the widget framed, wherever it came to rest: moved,
        // refused (nowhere to go) or put back by Back alike. The frame waits for the settle.
        viewModelScope.launch {
            var carried: DragSource? = null
            dragging.drag.collect { drag ->
                val source = drag?.source
                if (source != null) {
                    carried = source
                } else {
                    val ended = carried ?: return@collect
                    carried = null
                    if (ended.kind == ItemKind.WIDGET && ended.itemId != 0L) {
                        widgetResize.show(ended.itemId)
                    }
                }
            }
        }
    }

    /**
     * A long press on a placed item: a widget on a page gets its resize frame, which has its
     * Remove; anything else gets the item menu.
     */
    val hold: (HomeItem, Container, Int, Bounds) -> Unit = { item, container, page, cell ->
        if (item.kind == ItemKind.WIDGET && container == Container.HOME) {
            widgetResize.show(item.id)
        } else {
            itemMenu.hold(item, container, page, cell)
        }
    }

    fun startDrag(item: HomeItem, container: Container, page: Int, pointer: Point, grab: Point) {
        itemMenu.dismiss()
        homeMenu.dismiss()
        widgetResize.dismiss()
        val source =
            DragSource(
                item.id,
                item.kind,
                container,
                page,
                item.x,
                item.y,
                item.spanX,
                item.spanY,
                identity = item.identity,
            )
        dragging.startDrag(source, pointer, grab)
    }

    /**
     * A drag of something with no cell yet: an app out of the drawer or an open folder, or a drawer
     * folder out of the drawer. Whatever was open closes under it, and the drop places it.
     */
    fun startNewDrag(source: DragSource, pointer: Point, grab: Point) {
        itemMenu.dismiss()
        homeMenu.dismiss()
        widgetResize.dismiss()
        widgetPicker.dismiss()
        closeDrawer()
        // The open folder's sheet slides away, as the drawer does under a drag; it closes after.
        _openFolder.value = _openFolder.value?.copy(leaving = true)
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
        homeMenu.dismiss()
        drawerQuery = ""
        _drawerOpen.value = true
    }

    /**
     * The query stays until the next open resets it: emptying it now would reflow the rows
     * mid-close.
     */
    fun closeDrawer() {
        _drawerOpen.value = false
    }

    /**
     * Enter in the drawer's search field launches the best match, if there is one. Nothing typed
     * means nothing asked for: an empty query matches every app, and the first of those is not what
     * anyone meant.
     */
    fun launchDrawerMatch() {
        if (drawerQuery.isBlank()) return
        state.value.apps.matching(drawerQuery).firstOrNull()?.let(::launch)
    }

    /**
     * Back with nothing of its own to close: the drawer, folders and menus handle their own. A drag
     * is put back; the bare home screen stays as it is. Without this the activity would finish, as
     * any activity does on Back, and the system would start the launcher again from nothing.
     */
    fun onBack() {
        if (dragging.drag.value != null) dragging.cancelDrag()
    }

    /** HOME closes whatever is open over the pages; with nothing open it returns to page 1. */
    fun onHomeIntent() {
        when {
            // A drag is the most open thing there is: the icon goes back where it was, and the
            // pages stay put under the finger rather than sliding away beneath it.
            dragging.drag.value != null -> dragging.cancelDrag()
            itemMenu.isOpen -> itemMenu.dismiss()
            homeMenu.isOpen -> homeMenu.dismiss()
            widgetResize.isShown -> widgetResize.dismiss()
            widgetPicker.isOpen -> widgetPicker.dismiss()
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
 * Writes [move] as the drop it is: a drawer folder placed, a fold, an app out of a folder, an app
 * from the drawer, a placement moved out of a folder's stead, or a placement moved; each with the
 * neighbours it displaced moved aside first. False, and nothing changed, when the layout no longer
 * matches the plan.
 */
internal suspend fun WorkspaceRepository.apply(move: PendingMove): Boolean =
    with(move) {
        val app = newApp
        val into = foldInto
        val outOf = fromFolder
        val folder = newFolder
        when {
            folder != null -> placeFolder(folder, container, page, x, y, displaced)
            into != null ->
                foldInto(
                    into,
                    if (app != null) Dropped.App(app) else Dropped.Item(itemId),
                    outOf = outOf,
                )
            app != null && outOf != null -> unfold(outOf, app, container, page, x, y, displaced)
            app != null -> addApp(app, container, page, x, y, displaced)
            outOf != null -> moveOutOf(outOf, itemId, container, page, x, y, displaced)
            else -> moveItem(itemId, container, page, x, y, displaced)
        }
    }

/**
 * Whether what [menu] opened on is still there: the app, for one held in the drawer's list, which
 * is no placement; the placement itself for anything else, drawer folder tiles included.
 */
internal fun HomeState.stillHas(menu: ItemMenuState): Boolean =
    if (menu.container == Container.DRAWER && menu.item.folderId == null) {
        apps.any { it.key == menu.item.app?.key }
    } else {
        allItems().any { it.id == menu.item.id }
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
        move.newFolder != null -> folderId == move.newFolder
        // The key is the profile and the provider; a placement stores the provider alone.
        move.newWidget != null ->
            kind == ItemKind.WIDGET && provider == move.newWidget.substringAfter('/')
        newApp != null && move.foldInto != null -> folder.any { it.ref == newApp }
        newApp != null -> app?.ref == newApp
        else -> id == move.itemId
    }
}
