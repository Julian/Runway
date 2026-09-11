package com.grayvines.runway.ui.widgets

import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.addWidget
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.LayoutEngine
import com.grayvines.runway.system.widgets.WidgetProvider
import com.grayvines.runway.ui.attempt
import com.grayvines.runway.ui.drag.PendingMove
import com.grayvines.runway.ui.drag.WorkspaceLookup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The widget picker's state and what choosing a widget does. Tapped, it is bound and put on the
 * page being shown, in the first cells that fit it, at the size its provider designed it for when
 * there is room for that and at its smallest otherwise. Dragged out, it is bound and put where it
 * was dropped ([place]). What cannot be done is said in a [notice].
 */
class WidgetPickerHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val lookup: WorkspaceLookup,
    /** The home page on screen, where a tapped widget goes. */
    private val shownPage: () -> Int,
    /** Whether the picker may open now: not over a live drag. */
    private val mayOpen: () -> Boolean = { true },
) {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    /** What the device offers, loaded as the picker opens; null while that is under way. */
    private val _providers = MutableStateFlow<List<WidgetProvider>?>(null)
    val providers: StateFlow<List<WidgetProvider>?> = _providers

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Why a chosen widget was not added, for the user. */
    val notices: SharedFlow<String> = _notices

    val isOpen: Boolean
        get() = _open.value

    fun open() {
        if (!mayOpen()) return
        _open.value = true
        _providers.value = null
        scope.launch {
            attempt("list the widgets") {
                _providers.value = withContext(Dispatchers.Default) { graph.widgets.providers() }
            }
        }
    }

    fun dismiss() {
        _open.value = false
    }

    /**
     * The size [provider] is carried at when dragged out of a grid whose cells are [cellWidthDp] ×
     * [cellHeightDp]: the designed one if the grid holds it, else the smallest.
     */
    fun spanFor(provider: WidgetProvider, cellWidthDp: Float, cellHeightDp: Float): WidgetSpan =
        spans(provider, cellWidthDp, cellHeightDp).first()

    /** [provider] was tapped over a grid whose cells are [cellWidthDp] × [cellHeightDp]. */
    fun pick(provider: WidgetProvider, cellWidthDp: Float, cellHeightDp: Float) {
        dismiss()
        scope.launch { attempt("add the widget") { add(provider, cellWidthDp, cellHeightDp) } }
    }

    /**
     * A widget dragged out of the picker was dropped: [move] names its provider key, its cells and
     * the neighbours to move aside. False, and nothing added, when it could not be.
     */
    suspend fun place(move: PendingMove): Boolean {
        val provider = _providers.value?.firstOrNull { it.key == move.newWidget } ?: return false
        val id = bound(provider) ?: return false
        val at = Footprint(move.x, move.y, move.spanX, move.spanY)
        return store(id, provider, move.page, at, move.displaced)
    }

    private suspend fun add(provider: WidgetProvider, cellWidthDp: Float, cellHeightDp: Float) {
        val id = bound(provider) ?: return
        val page = shownPage()
        val placed =
            spans(provider, cellWidthDp, cellHeightDp).firstNotNullOfOrNull { span ->
                LayoutEngine.findFreeCell(
                        lookup.grid,
                        lookup.homeItems(page),
                        span.width,
                        span.height,
                    )
                    ?.let { Footprint(it.x, it.y, span.width, span.height) }
            }
        if (placed == null) {
            graph.widgets.deleteId(id)
            _notices.tryEmit("No room on this page")
            return
        }
        store(id, provider, page, placed, emptyMap())
    }

    private fun spans(provider: WidgetProvider, cellWidthDp: Float, cellHeightDp: Float) =
        spansFor(
            WidgetSpan(provider.info.targetCellWidth, provider.info.targetCellHeight).takeIf {
                it.width > 0
            },
            provider.minWidthDp,
            provider.minHeightDp,
            cellWidthDp,
            cellHeightDp,
            lookup.grid,
        )

    /** A fresh id bound to [provider], or null (and a notice) when that cannot be done yet. */
    private fun bound(provider: WidgetProvider): Int? {
        val host = graph.widgets
        val info = provider.info
        val id = host.allocateId()
        val refused =
            when {
                !host.bind(id, info.provider, info.profile) -> "Runway may not add widgets yet"
                provider.needsSetup -> "This widget needs setting up first, which is not done yet"
                else -> null
            }
        if (refused == null) return id
        host.deleteId(id)
        _notices.tryEmit(refused)
        return null
    }

    /** Stores the placement; the id goes back to the host if the layout would not take it. */
    private suspend fun store(
        id: Int,
        provider: WidgetProvider,
        page: Int,
        at: Footprint,
        displaced: Map<Long, Footprint>,
    ): Boolean {
        var stored = false
        attempt("store the widget") {
            stored =
                graph.workspace.addWidget(
                    id,
                    provider.info.provider.flattenToString(),
                    page,
                    at.x,
                    at.y,
                    at.width,
                    at.height,
                    displaced,
                ) != null
        }
        if (!stored) graph.widgets.deleteId(id)
        return stored
    }
}
