package com.grayvines.runway.ui.widgets

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.home.HomeState

/**
 * The resize frame as the screen sees it: which placement it is around, where that is, and what the
 * handles are doing. Read through [State], so only what changes recomposes.
 */
@Stable
class WidgetResizeSession(
    private val shownOf: State<Long?>,
    val onResize: (itemId: Long, to: Footprint) -> Unit,
    val onRemove: (HomeItem) -> Unit,
    val onDismiss: () -> Unit,
) {
    val shown: Long?
        get() = shownOf.value

    /** Where the framed widget's cell is on screen (root px), as the cell reports it. */
    private var anchor by mutableStateOf<Pair<Long, Rect>?>(null)

    /**
     * The cells the framed widget has while a handle is pulled, and after, until the layout shows
     * the result: the cell draws these rather than its stored footprint.
     */
    var preview by mutableStateOf<Pair<Long, Footprint>?>(null)
        internal set

    fun frames(itemId: Long) = shown == itemId

    fun anchorFor(itemId: Long): Rect? = anchor?.takeIf { it.first == itemId }?.second

    fun previewFor(itemId: Long): Footprint? = preview?.takeIf { it.first == itemId }?.second

    /** The framed widget's cell is at [bounds] (root px); anything else's is not of interest. */
    fun positioned(itemId: Long, bounds: Rect) {
        if (shown == itemId) anchor = itemId to bounds
    }
}

/** The one resize session, reading the live frame. */
@Composable
fun rememberWidgetResizeSession(host: WidgetResizeHost): WidgetResizeSession {
    val shown = host.shown.collectAsStateWithLifecycle()
    return remember(host) {
        WidgetResizeSession(shown, host::resize, host::remove, host::dismiss)
    }
}

/** [dismissesResize] for the live session, built once: a rebuilt block restarts mid-gesture. */
@Composable
fun Modifier.dismissingResize(session: WidgetResizeSession): Modifier {
    val current = rememberUpdatedState(session)
    return this.then(remember { Modifier.dismissesResize { current.value } })
}

/**
 * For the root: a touch that lands outside the frame (its cells and its handles' reach) puts the
 * frame away, and is otherwise untouched: it goes on to whatever it landed on, the icon or the
 * widget, as it would have with no frame up. Built once and kept: a pointer-input block that
 * changes identity restarts mid-gesture.
 */
fun Modifier.dismissesResize(session: () -> WidgetResizeSession): Modifier =
    pointerInput(Unit) {
        val reach = HANDLE_TOUCH.toPx() / 2
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val s = session()
            val frame = s.shown?.let(s::anchorFor)?.inflate(reach)
            if (s.shown != null && frame?.contains(down.position) != true) s.onDismiss()
        }
    }

/**
 * The resize frame around the widget it is shown for, once that widget is in its cell: not while it
 * is still settling there after a drop, and not before the layout shows a newly placed one.
 */
@Composable
fun WidgetResizeOverlay(
    state: HomeState,
    session: WidgetResizeSession,
    cell: DpSize,
    drag: DragSession,
) {
    val id = session.shown ?: return
    val page = state.homePages.firstOrNull { p -> p.items.any { it.id == id } } ?: return
    val item = page.items.first { it.id == id }
    if (drag.settling?.itemId == id) return
    val settings = state.settings
    WidgetResizeFrame(
        item = item,
        session = session,
        cell = cell,
        grid = GridSize(settings.columns, settings.pageRows),
        others = page.occupied.filter { it.id != id },
    )
}
