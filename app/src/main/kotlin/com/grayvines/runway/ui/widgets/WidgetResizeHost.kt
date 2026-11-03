package com.grayvines.runway.ui.widgets

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
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
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.resizeWidget
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.ui.attempt
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.home.HomeState
import com.grayvines.runway.ui.writing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The frame around one widget, with its handles, its Remove and its Edit: up after a hold on the
 * widget, after it is placed and after every move of it, until a touch anywhere else puts it away.
 * Read through Compose state, so only what changes recomposes.
 */
@Stable
class WidgetResizeHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    /** Whether the frame may show now: not over a live drag. */
    private val mayShow: () -> Boolean = { true },
    /** Returns once the screen shows [itemId] at [to], or gives up. */
    private val awaitReflected: suspend (itemId: Long, to: Footprint) -> Unit = { _, _ -> },
) {
    /** Set while an activity is up to run a widget's setup screen; see [WidgetPrompts]. */
    var prompts: WidgetPrompts? = null

    /** The placement the frame is around, if any. */
    var shown by mutableStateOf<Long?>(null)
        private set

    /** Where the framed widget's cell is on screen (root px), as the cell reports it. */
    var anchor by mutableStateOf<Rect?>(null)
        private set

    /** Where the frame's Remove and Edit chips sit (root px), so tapping one never dismisses. */
    var chips by mutableStateOf<Rect?>(null)
        internal set

    /**
     * The cells the framed widget has while a handle is pulled, and after, until the layout shows
     * the result: the cell draws these rather than its stored footprint.
     */
    var preview by mutableStateOf<Footprint?>(null)
        internal set

    /**
     * Where single-cell neighbours pushed aside by [preview] go while the handle is pulled, and
     * after, until the layout shows the result.
     */
    var displaced by mutableStateOf<Map<Long, Footprint>>(emptyMap())
        internal set

    /** A handle is under a finger: the preview is the pull's, whether or not it changes. */
    var pulling by mutableStateOf(false)
        internal set

    /**
     * The finger's whole pull on a handle as a rectangle (root px), beyond what the widget can take
     * too, while a handle is pulled; else null. What the search bar sees pressing against it.
     */
    var outline by mutableStateOf<Rect?>(null)
        internal set

    val isShown: Boolean
        get() = shown != null

    fun frames(itemId: Long) = shown == itemId

    fun resizing(itemId: Long) = shown == itemId && preview != null

    fun previewFor(itemId: Long): Footprint? =
        preview?.takeIf { shown == itemId } ?: displaced[itemId]

    internal fun clearPreview() {
        preview = null
        displaced = emptyMap()
    }

    /** The framed widget's cell is at [bounds] (root px); anything else's is not of interest. */
    fun positioned(itemId: Long, bounds: Rect) {
        if (shown == itemId) anchor = bounds
    }

    fun show(itemId: Long) {
        if (!mayShow()) return
        if (shown != itemId) {
            shown = itemId
            anchor = null
            chips = null
            outline = null
            pulling = false
            clearPreview()
        }
    }

    fun dismiss() {
        shown = null
        anchor = null
        chips = null
        outline = null
        pulling = false
        clearPreview()
    }

    /** A handle was let go with the widget over [to], pushing [displaced] aside. */
    fun resize(itemId: Long, to: Footprint, displaced: Map<Long, Footprint> = emptyMap()) {
        scope.launch {
            val saved =
                attempt("resize the widget") {
                    check(graph.workspace.resizeWidget(itemId, to, displaced)) {
                        "the widget or its page changed under the frame; nothing saved"
                    }
                }
            if (saved) awaitReflected(itemId, to)
            if (!pulling && shown == itemId && preview == to) clearPreview()
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

/** [dismissesResize] for the live host, built once: a rebuilt block restarts mid-gesture. */
@Composable
fun Modifier.dismissingResize(host: WidgetResizeHost): Modifier {
    val current = rememberUpdatedState(host)
    return this.then(remember { Modifier.dismissesResize { current.value } })
}

/**
 * For the root: a touch that lands outside the frame (its cells and its handles' reach) puts the
 * frame away, and is otherwise untouched: it goes on to whatever it landed on, the icon or the
 * widget, as it would have with no frame up. Built once and kept: a pointer-input block that
 * changes identity restarts mid-gesture.
 */
fun Modifier.dismissesResize(host: () -> WidgetResizeHost): Modifier =
    pointerInput(Unit) {
        val reach = HANDLE_TOUCH.toPx() / 2
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val h = host()
            val frame = h.anchor?.inflate(reach)
            val inFrame =
                frame?.contains(down.position) == true || h.chips?.contains(down.position) == true
            if (h.shown != null && !inFrame) h.dismiss()
        }
    }

/**
 * The resize frame around the widget it is shown for, once that widget is in its cell: not while it
 * is still settling there after a drop, and not before the layout shows a newly placed one.
 */
@Composable
fun WidgetResizeOverlay(
    state: HomeState,
    host: WidgetResizeHost,
    cell: DpSize,
    drag: DragSession,
) {
    val id = host.shown ?: return
    val page = state.homePages.firstOrNull { p -> p.items.any { it.id == id } } ?: return
    val item = page.items.first { it.id == id }
    if (drag.settling?.itemId == id) return
    val settings = state.settings
    WidgetResizeFrame(
        item = item,
        host = host,
        cell = cell,
        grid = GridSize(settings.columns, settings.pageRows),
        others = page.occupied.filter { it.id != id },
    )
}
