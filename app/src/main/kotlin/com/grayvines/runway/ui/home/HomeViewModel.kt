package com.grayvines.runway.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import com.grayvines.runway.ui.drag.DragController
import com.grayvines.runway.ui.drag.DragSource
import com.grayvines.runway.ui.drag.DragState
import com.grayvines.runway.ui.drag.DropAreas
import com.grayvines.runway.ui.drag.DropTarget
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.WorkspaceLookup
import com.grayvines.runway.ui.drag.targetFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    val loaded: Boolean = false,
)

class HomeViewModel(private val graph: AppGraph) : ViewModel() {
    private val _goHome = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires when the HOME intent arrives while already showing. */
    val goHome: SharedFlow<Unit> = _goHome

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
                    loaded = true,
                )
            }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), HomeState())

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

    private val dragController = DragController(lookup)

    /** The drag in progress, if any. */
    val drag: StateFlow<DragState?> = dragController.state

    /** Where home and dock currently are on screen; the UI keeps this current. */
    var dropAreas: DropAreas = DropAreas()

    init {
        viewModelScope.launch { graph.workspace.ensureInitialised() }
    }

    fun startDrag(item: HomeItem, container: Container, page: Int, pointer: Point, grab: Point) {
        val source =
            DragSource(item.id, item.kind, container, page, item.x, item.y, item.spanX, item.spanY)
        dragController.start(source, pointer, grab)
    }

    fun dragTo(pointer: Point) {
        val current = drag.value ?: return
        val target: DropTarget? =
            dropAreas.targetFor(pointer, current.grab, current.source.spanX, current.source.spanY)
        dragController.move(pointer, target)
    }

    fun endDrag() {
        val source = drag.value?.source ?: return
        val move = dragController.drop() ?: return
        viewModelScope.launch {
            when (val target = move.target) {
                is DropTarget.HomeCell ->
                    graph.workspace.moveItem(
                        source.itemId,
                        Container.HOME,
                        target.page,
                        target.x,
                        target.y,
                        move.displaced,
                    )
                is DropTarget.DockSlot ->
                    graph.workspace.moveItem(
                        source.itemId,
                        Container.DOCK,
                        target.page,
                        target.slot,
                        0,
                        emptyMap(),
                    )
            }
        }
    }

    fun cancelDrag() = dragController.cancel()

    fun launch(item: HomeItem) {
        item.app?.let(graph.appRepository::launch)
    }

    fun onHomeIntent() {
        _goHome.tryEmit(Unit)
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
    }
}
