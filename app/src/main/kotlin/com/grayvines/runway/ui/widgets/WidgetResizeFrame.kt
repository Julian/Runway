package com.grayvines.runway.ui.widgets

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.grayvines.runway.appGraph
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.model.GridSize
import com.grayvines.runway.model.Placed
import com.grayvines.runway.ui.home.HomeItem
import kotlinx.coroutines.delay

const val WIDGET_RESIZE_TAG = "widget-resize"

private val STROKE = 2.dp
private val CORNER = 12.dp
internal val HANDLE_TOUCH = 44.dp
private val HANDLE_LENGTH = 26.dp
private val HANDLE_THICKNESS = 6.dp
private val REMOVE_SIZE = 28.dp
private val REMOVE_ICON = 18.dp
private const val FILL_ALPHA = 0.06f
private const val OUTLINE_ALPHA = 0.9f
private val CHIP = Color(0xFF202124)

/**
 * How long a resize is shown as pulled after the handle is let go, if the layout never shows it.
 */
internal const val REFLECT_TIMEOUT_MS = 3_000L

/**
 * The frame around [item] (root coordinates, over everything): an outline on its cells, a handle on
 * each edge its provider lets move, and Remove at its corner. A handle pulled snaps the widget a
 * cell at a time, as far as its limits, the grid and its neighbours ([others]) allow, while the
 * outline follows the finger; let go, the size is saved. A touch anywhere else puts the frame away,
 * and so does back.
 */
@Composable
fun WidgetResizeFrame(
    item: HomeItem,
    session: WidgetResizeSession,
    cell: DpSize,
    grid: GridSize,
    others: List<Placed>,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val limits =
        remember(item.appWidgetId, cell, grid) {
            item.appWidgetId?.let(context.appGraph.widgets::info).limits(cell, grid, density)
        }
    val cellPx = with(density) { Size(cell.width.toPx(), cell.height.toPx()) }
    val pull =
        remember(session, item.id, limits, grid, cellPx) {
            Pull(session, item.id, limits, grid, cellPx)
        }
    pull.others = others
    pull.footprint = session.previewFor(item.id) ?: item.footprint
    BackHandler(onBack = session.onDismiss)
    Reflected(item, session)
    // No pointer input of its own: a sibling that took touches would keep every one of them from
    // the pages beneath, hit or not. Touches outside are watched from the root ([dismissesResize]).
    Box(Modifier.fillMaxSize()) {
        val anchor = session.anchorFor(item.id)
        if (anchor != null) {
            pull.anchor = anchor
            Frame(pull, limits) { session.onRemove(item) }
        }
    }
}

/**
 * The outline, handles and Remove; the outline is read as it is drawn, not composed. No surface
 * over the widget: the cell beneath keeps its gestures (a hold lifts the framed widget again) and
 * mutes the widget's own view itself while framed ([WidgetCell]).
 */
@Composable
private fun Frame(pull: Pull, limits: ResizeLimits, onRemove: () -> Unit) {
    Box(Modifier.fillMaxSize().testTag(WIDGET_RESIZE_TAG)) {
        Canvas(Modifier.fillMaxSize()) {
            val outline = pull.outline()
            val corner = CornerRadius(CORNER.toPx())
            drawRoundRect(
                Color.White.copy(alpha = FILL_ALPHA),
                outline.topLeft,
                outline.size,
                corner,
            )
            drawRoundRect(
                Color.White.copy(alpha = OUTLINE_ALPHA),
                outline.topLeft,
                outline.size,
                corner,
                style = Stroke(STROKE.toPx()),
            )
        }
        limits.edges.forEach { edge -> Handle(edge, pull) }
        RemoveChip(pull::outline, onRemove)
    }
}

/** One edge's handle, centred on that edge of the outline; pulling it resizes the widget. */
@Composable
private fun Handle(edge: Edge, pull: Pull) {
    val density = LocalDensity.current
    val touch = with(density) { HANDLE_TOUCH.roundToPx() }
    val label =
        when (edge) {
            Edge.LEFT -> "Left edge"
            Edge.TOP -> "Top edge"
            Edge.RIGHT -> "Right edge"
            Edge.BOTTOM -> "Bottom edge"
        }
    Box(
        Modifier.offset {
                val c = pull.outline().midpoint(edge)
                IntOffset((c.x - touch / 2).toInt(), (c.y - touch / 2).toInt())
            }
            .size(HANDLE_TOUCH)
            .semantics { contentDescription = label }
            .pointerInput(edge, pull) {
                detectDragGestures(
                    onDragStart = { pull.start(edge) },
                    onDrag = { change, delta ->
                        change.consume()
                        pull.moved(delta)
                    },
                    onDragEnd = pull::end,
                    onDragCancel = pull::end,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val pill =
            if (edge.horizontal) {
                DpSize(HANDLE_THICKNESS, HANDLE_LENGTH)
            } else {
                DpSize(HANDLE_LENGTH, HANDLE_THICKNESS)
            }
        Box(Modifier.size(pill).background(Color.White, CircleShape))
    }
}

/** Remove, at the outline's top-right corner. */
@Composable
private fun RemoveChip(outline: () -> Rect, onRemove: () -> Unit) {
    val density = LocalDensity.current
    val size = with(density) { REMOVE_SIZE.roundToPx() }
    Box(
        Modifier.offset {
                val r = outline()
                IntOffset((r.right - size / 2).toInt(), (r.top - size / 2).toInt())
            }
            .size(REMOVE_SIZE)
            .background(CHIP, CircleShape)
            .clickable(onClick = onRemove)
            .semantics { contentDescription = "Remove" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.Clear,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(REMOVE_ICON),
        )
    }
}

/**
 * Keeps the preview only until the layout shows the saved size, or for a while after the handle is
 * let go if it never does (the write failed: the widget goes back to its stored cells). While a
 * finger is on a handle the preview is the pull's, however long it rests.
 */
@Composable
private fun Reflected(item: HomeItem, session: WidgetResizeSession) {
    val preview = session.previewFor(item.id)
    val pulling = session.pulling
    LaunchedEffect(preview, item.footprint, pulling) {
        if (preview != null && preview == item.footprint) {
            session.preview = null
        } else if (preview != null && !pulling) {
            delay(REFLECT_TIMEOUT_MS)
            if (session.previewFor(item.id) == preview) session.preview = null
        }
    }
}

/**
 * The handles' pull on one widget: from a finger's first move on a handle to its lift, the widget
 * snaps cell by cell while the outline's pulled edge follows the finger within the widget's limits.
 */
private class Pull(
    private val session: WidgetResizeSession,
    private val itemId: Long,
    private val limits: ResizeLimits,
    private val grid: GridSize,
    private val cellPx: Size,
) {
    /** The page's other items, which the widget may not grow over. */
    var others: List<Placed> = emptyList()

    /** The cells the widget has now, previewed or stored. */
    var footprint = Footprint(0, 0)

    /** Where the widget's cell is (root px); state, so the frame follows the cell as it grows. */
    var anchor by mutableStateOf(Rect.Zero)

    private var edge: Edge? = null

    /**
     * How far outward the edge is drawn pulled, in px, read as the frame is drawn: the finger's
     * pull, kept within what the widget can take ([reach]), so the outline never shows room it
     * cannot have.
     */
    private var px by mutableStateOf(0f)

    /** The finger's whole pull, px, unclamped: what presses against the search bar. */
    private var pulled = 0f
    private var within = 0f..0f
    private var base = Rect.Zero
    private var origin = Footprint(0, 0)

    /** The whole cells the pull has snapped to, outward from [origin]. */
    private var cells = 0

    fun start(edge: Edge) {
        session.pulling = true
        this.edge = edge
        px = 0f
        pulled = 0f
        cells = 0
        origin = footprint
        base = anchor
        val cell = if (edge.horizontal) cellPx.width else cellPx.height
        val cellsWithin = reach(origin, edge, limits, grid, others)
        within = cellsWithin.first * cell..cellsWithin.last * cell
    }

    fun moved(delta: Offset) {
        val e = edge ?: return
        val outward =
            when (e) {
                Edge.LEFT -> -delta.x
                Edge.RIGHT -> delta.x
                Edge.TOP -> -delta.y
                Edge.BOTTOM -> delta.y
            }
        val cell = if (e.horizontal) cellPx.width else cellPx.height
        pulled += outward
        px = pulled.coerceIn(within.start, within.endInclusive)
        cells = cellsPulled(px, cell, cells)
        session.preview = itemId to resized(origin, e, cells, limits, grid, others)
        session.outline = outline(pulled)
    }

    fun end() {
        session.pulling = false
        session.outline = null
        val to = session.previewFor(itemId)
        edge = null
        px = 0f
        pulled = 0f
        if (to != null && to != origin) session.onResize(itemId, to)
    }

    /** The outline as drawn: the widget's cell with the pulled edge as far as it can go. */
    fun outline(): Rect = outline(px)

    /** The widget's cell with the pulled edge [by] px outward. */
    private fun outline(by: Float): Rect {
        val a = anchor
        return when (edge) {
            null -> a
            Edge.LEFT -> Rect(base.left - by, a.top, a.right, a.bottom)
            Edge.RIGHT -> Rect(a.left, a.top, base.right + by, a.bottom)
            Edge.TOP -> Rect(a.left, base.top - by, a.right, a.bottom)
            Edge.BOTTOM -> Rect(a.left, a.top, a.right, base.bottom + by)
        }
    }
}

private fun Rect.midpoint(edge: Edge): Offset =
    when (edge) {
        Edge.LEFT -> Offset(left, center.y)
        Edge.RIGHT -> Offset(right, center.y)
        Edge.TOP -> Offset(center.x, top)
        Edge.BOTTOM -> Offset(center.x, bottom)
    }

/**
 * A provider's limits in this grid; a widget with no provider (its app is gone) cannot resize. The
 * smallest size a widget can be pulled to is its resize minimum when it names one below its size.
 */
private fun AppWidgetProviderInfo?.limits(cell: DpSize, grid: GridSize, density: Density) =
    if (this == null) {
        ResizeLimits(1, 1, 1, 1, horizontal = false, vertical = false)
    } else {
        val scale = density.density
        resizeLimits(
            minWidthDp = least(minWidth, minResizeWidth) / scale,
            minHeightDp = least(minHeight, minResizeHeight) / scale,
            maxWidthDp = maxResizeWidth / scale,
            maxHeightDp = maxResizeHeight / scale,
            horizontal = resizeMode and AppWidgetProviderInfo.RESIZE_HORIZONTAL != 0,
            vertical = resizeMode and AppWidgetProviderInfo.RESIZE_VERTICAL != 0,
            cellWidthDp = cell.width.value,
            cellHeightDp = cell.height.value,
            grid = grid,
        )
    }

/** [resizeMin] counts only when set and below [min], as the framework reads it. */
private fun least(min: Int, resizeMin: Int) = if (resizeMin in 1 until min) resizeMin else min
