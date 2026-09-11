package com.grayvines.runway.ui.widgets

import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.resizeWidget
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.writing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The frame around one widget, with its handles and its Remove: up after a hold on the widget,
 * after it is placed and after every move of it, until a touch anywhere else puts it away.
 */
class WidgetResizeHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    /** Whether the frame may show now: not over a live drag. */
    private val mayShow: () -> Boolean = { true },
) {
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
