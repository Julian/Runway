package com.grayvines.runway.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ContainerContent
import com.grayvines.runway.data.ItemEntity
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.settings.Settings
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.search.SearchTarget
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
)

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

    init {
        viewModelScope.launch { graph.workspace.ensureInitialised() }
    }

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
