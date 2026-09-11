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
    /** Told the placement id of every widget added. */
    private val onPlaced: (itemId: Long) -> Unit = {},
) {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open

    /** What the device offers, loaded as the picker opens; null while that is under way. */
    private val _providers = MutableStateFlow<List<WidgetProvider>?>(null)
    val providers: StateFlow<List<WidgetProvider>?> = _providers

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Why a chosen widget was not added, for the user. */
    val notices: SharedFlow<String> = _notices

    /**
     * The activity's, while there is one: the system's dialogs a widget may need before it is
     * added.
     */
    var prompts: WidgetPrompts? = null

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

    /**
     * Room first, then the binding: the system's prompt and a widget's setup screen are not worth
     * answering for a widget with nowhere to go. The page can fill meanwhile; [store] then refuses
     * and gives the id back.
     */
    private suspend fun add(provider: WidgetProvider, cellWidthDp: Float, cellHeightDp: Float) {
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
            _notices.tryEmit("No room on this page")
            return
        }
        val id = bound(provider) ?: return
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

    /**
     * A fresh id bound to [provider] and set up if the widget insists on it, or null when the user
     * would not have it: the system asks their leave to bind the first time, and a widget's setup
     * screen can be cancelled. Only a refused bind is worth a notice; a cancelled setup was theirs.
     */
    private suspend fun bound(provider: WidgetProvider): Int? {
        val host = graph.widgets
        val info = provider.info
        val id = host.allocateId()
        val allowed =
            host.bind(id, info.provider, info.profile) ||
                prompts?.requestBind(id, info.provider, info.profile) == true
        if (!allowed) {
            host.deleteId(id)
            _notices.tryEmit("Runway was not allowed to add widgets")
            return null
        }
        if (provider.needsSetup && prompts?.configure(id) != true) {
            host.deleteId(id)
            return null
        }
        return id
    }

    /** Stores the placement; the id goes back to the host if the layout would not take it. */
    private suspend fun store(
        id: Int,
        provider: WidgetProvider,
        page: Int,
        at: Footprint,
        displaced: Map<Long, Footprint>,
    ): Boolean {
        var stored: Long? = null
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
                )
        }
        val itemId = stored
        if (itemId == null) graph.widgets.deleteId(id) else onPlaced(itemId)
        return itemId != null
    }
}
