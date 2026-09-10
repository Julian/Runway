package com.grayvines.runway.ui.widgets

import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.addWidget
import com.grayvines.runway.model.LayoutEngine
import com.grayvines.runway.system.widgets.WidgetProvider
import com.grayvines.runway.ui.attempt
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
 * The widget picker's state and what choosing a widget does: it is bound and put on the page being
 * shown, in the first cells that fit it, at the size its provider designed it for when there is
 * room for that and at its smallest otherwise. What cannot be done is said in a [notice].
 */
class WidgetPickerHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val lookup: WorkspaceLookup,
    /** The home page on screen, where a chosen widget goes. */
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

    /** [provider] was chosen from a grid whose cells are [cellWidthDp] × [cellHeightDp]. */
    fun pick(provider: WidgetProvider, cellWidthDp: Float, cellHeightDp: Float) {
        dismiss()
        scope.launch { attempt("add the widget") { add(provider, cellWidthDp, cellHeightDp) } }
    }

    private suspend fun add(provider: WidgetProvider, cellWidthDp: Float, cellHeightDp: Float) {
        val host = graph.widgets
        val info = provider.info
        val id = host.allocateId()
        val refused =
            when {
                !host.bind(id, info.provider, info.profile) -> "Runway may not add widgets yet"
                provider.needsSetup -> "This widget needs setting up first, which is not done yet"
                else -> null
            }
        if (refused != null) {
            host.deleteId(id)
            _notices.tryEmit(refused)
            return
        }
        val page = shownPage()
        val grid = lookup.grid
        val target = WidgetSpan(info.targetCellWidth, info.targetCellHeight).takeIf { it.width > 0 }
        val placed =
            spansFor(
                    target,
                    provider.minWidthDp,
                    provider.minHeightDp,
                    cellWidthDp,
                    cellHeightDp,
                    grid,
                )
                .firstNotNullOfOrNull { span ->
                    LayoutEngine.findFreeCell(grid, lookup.homeItems(page), span.width, span.height)
                        ?.let { span to it }
                }
        if (placed == null) {
            host.deleteId(id)
            _notices.tryEmit("No room on this page")
            return
        }
        val (span, cell) = placed
        val stored =
            attempt("store the widget") {
                graph.workspace.addWidget(
                    id,
                    info.provider.flattenToString(),
                    page,
                    cell.x,
                    cell.y,
                    span.width,
                    span.height,
                )
            }
        if (!stored) host.deleteId(id)
    }
}
