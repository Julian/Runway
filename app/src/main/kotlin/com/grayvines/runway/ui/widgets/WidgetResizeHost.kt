package com.grayvines.runway.ui.widgets

import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.resizeWidget
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.attempt
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.writing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The frame around one widget, with its handles, its Remove and its Edit: up after a hold on the
 * widget, after it is placed and after every move of it, until a touch anywhere else puts it away.
 */
class WidgetResizeHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    /** Whether the frame may show now: not over a live drag. */
    private val mayShow: () -> Boolean = { true },
) {
    /** Set while an activity is up to run a widget's setup screen; see [WidgetPrompts]. */
    var prompts: WidgetPrompts? = null

    private val _shown = MutableStateFlow<Long?>(null)

    /** The placement the frame is around, if any. */
    val shown: StateFlow<Long?> = _shown

    val isShown: Boolean
        get() = _shown.value != null

    fun show(itemId: Long) {
        if (mayShow()) _shown.value = itemId
    }

    fun dismiss() {
        _shown.value = null
    }

    /** A handle was let go with the widget over [to]. */
    fun resize(itemId: Long, to: Footprint) {
        scope.writing("resize the widget") {
            check(graph.workspace.resizeWidget(itemId, to)) {
                "the widget or its page changed under the frame; nothing saved"
            }
        }
    }

    /**
     * The frame's Edit: the widget's own setup screen, for the id it already has, so it keeps its
     * cells and whatever it was showing. The frame goes, since the screen takes the display.
     */
    fun reconfigure(item: HomeItem) {
        val id = item.appWidgetId ?: return
        dismiss()
        scope.launch { attempt("open the widget's own settings") { prompts?.configure(id) } }
    }

    /** The frame's Remove: the placement goes, and the host id with it. */
    fun remove(item: HomeItem) {
        dismiss()
        scope.writing("remove the widget") {
            graph.workspace.removeItem(item.id)
            // Or the provider would go on thinking it is placed.
            item.appWidgetId?.let(graph.widgets::deleteId)
        }
    }
}
