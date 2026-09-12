package com.grayvines.runway.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.animateIntSizeAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.widgets.WidgetCell
import com.grayvines.runway.ui.widgets.WidgetResizeSession

/**
 * One page of cells: every item at its footprint, sized by its span. While a drag plans
 * displacement, displaced items are shown at their planned cells, and any change of cell slides
 * rather than jumps. The item being dragged, and the one settling after a drop, are drawn by
 * [DragOverlay] instead; their cells stay invisible here and the settling one reports where it is
 * so the overlay can bring the icon to it. A widget is drawn by its own host view over its cells,
 * which are those a [resize] handle is pulling it to while one is.
 */
@Composable
internal fun GridPage(
    items: List<HomeItem>,
    cell: DpSize,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    drag: DragSession?,
    handlersFor: (HomeItem) -> DragHandlers?,
    onHoldEmpty: ((Point) -> Unit)?,
    modifier: Modifier = Modifier,
    resize: WidgetResizeSession? = null,
    /** The cells the carried item would take on this page if let go now; outlined while it is. */
    landing: () -> Footprint? = { null },
) {
    val density = LocalDensity.current
    val cellW = with(density) { cell.width.roundToPx() }
    val cellH = with(density) { cell.height.roundToPx() }
    // The page's own long press (on empty space) takes the same hold as an icon's lift.
    CompositionLocalProvider(LocalViewConfiguration provides rememberLiftConfiguration()) {
        GridLayout(items, cellW, cellH, onHoldEmpty, modifier.outlines(landing, cellW, cellH)) {
            items.forEach { item ->
                key(item.id) {
                    val cellDrag = rememberCellDrag(item, drag, resize)
                    val settlingHere = cellDrag.settlingHere
                    val placement = Modifier.then(rememberCellPlacement(cellDrag, cellW, cellH))
                    if (item.kind == ItemKind.WIDGET) {
                        WidgetCell(
                            item,
                            cell,
                            modifier = placement,
                            drag = handlersFor(item),
                            session = drag,
                            lifted = cellDrag.lifted || settlingHere != null,
                            settlingHere = settlingHere,
                            framedBy = resize?.takeIf { cellDrag.framed },
                        )
                    } else {
                        IconCell(
                            item,
                            cellDrag,
                            iconSize,
                            labels,
                            onLaunch,
                            handlersFor(item),
                            placement,
                        )
                    }
                }
            }
        }
    }
}

/**
 * An app or folder in its cell, lifted or receiving as the drag says; settling reports its cell.
 */
@Composable
private fun IconCell(
    item: HomeItem,
    cellDrag: CellDrag,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    handlers: DragHandlers?,
    modifier: Modifier,
) {
    val settlingHere = cellDrag.settlingHere
    ItemCell(
        item,
        iconSize = iconSize,
        labels = labels,
        onClick = { cell -> onLaunch(item, cell) },
        drag = handlers,
        lifted = cellDrag.lifted || settlingHere != null,
        receiving = cellDrag.receiving,
        modifier =
            if (settlingHere != null) {
                modifier.onGloballyPositioned {
                    val c = it.boundsInRoot().center
                    settlingHere.onSettleTargetPositioned(Point(c.x, c.y))
                }
            } else {
                modifier
            },
    )
}

/**
 * Where a cell goes and how big it is (px, mid-animation included), read by the page's measure and
 * placement: through [State], so a cell that slides or grows costs a re-layout, not a
 * recomposition. Carried by the cell itself: a list filled as the cells compose could be read by a
 * measure before it is.
 */
private class CellPlacement(val position: State<IntOffset>, val size: State<IntSize>) :
    ParentDataModifier {
    override fun Density.modifyParentData(parentData: Any?) = this@CellPlacement
}

/** The cell's placement, animated: any change of cell slides, and a resize eases. */
@Composable
private fun rememberCellPlacement(cellDrag: CellDrag, cellW: Int, cellH: Int): CellPlacement {
    val f = cellDrag.footprint
    val position =
        animateIntOffsetAsState(
            IntOffset(f.x * cellW, f.y * cellH),
            // The overlay carries a settling item to its cell; the cell itself must already be
            // there, not sliding over. A widget under a resize handle eases to each cell it snaps
            // to, its size in step.
            when {
                cellDrag.settlingHere != null -> snap()
                cellDrag.resizing -> resizeTween()
                else -> spring(stiffness = Spring.StiffnessMediumLow)
            },
            label = "cell",
        )
    val size =
        animateIntSizeAsState(
            IntSize(f.width * cellW, f.height * cellH),
            if (cellDrag.resizing) resizeTween() else snap(),
            label = "cell size",
        )
    return CellPlacement(position, size)
}

/** How long a widget takes to reach the cells a resize handle snapped it to. */
private const val RESIZE_MS = 150

private fun <T> resizeTween() = tween<T>(RESIZE_MS, easing = FastOutSlowInEasing)

/** A faint rounded outline over the cells the carried item would land on: a hint, not a target. */
private const val LANDING_ALPHA = 0.45f
private const val LANDING_FILL_ALPHA = 0.08f
private val LANDING_INSET = 3.dp
private val LANDING_STROKE = 1.5.dp
private val LANDING_CORNER = 12.dp

/**
 * Draws [landing]'s cells, when there are any, as a subtle outline. Read in the draw phase only: a
 * finger's every move changes the plan, and this must cost a redraw, not a recomposition.
 */
private fun Modifier.outlines(landing: () -> Footprint?, cellW: Int, cellH: Int): Modifier =
    drawWithContent {
        drawContent()
        landing()?.let { f ->
            val inset = LANDING_INSET.toPx()
            val topLeft = Offset(f.x * cellW + inset, f.y * cellH + inset)
            val size = Size(f.width * cellW - 2 * inset, f.height * cellH - 2 * inset)
            val corner = CornerRadius(LANDING_CORNER.toPx())
            drawRoundRect(Color.White.copy(alpha = LANDING_FILL_ALPHA), topLeft, size, corner)
            drawRoundRect(
                Color.White.copy(alpha = LANDING_ALPHA),
                topLeft,
                size,
                corner,
                style = Stroke(LANDING_STROKE.toPx()),
            )
        }
    }

/**
 * Every item at its footprint, sized by its span, as each cell's [CellPlacement] says; the page
 * itself fills what it is given.
 */
@Composable
private fun GridLayout(
    items: List<HomeItem>,
    cellW: Int,
    cellH: Int,
    onHoldEmpty: ((Point) -> Unit)?,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    Layout(
        content = content,
        modifier = modifier.fillMaxSize().holdsEmptyCells(items, cellW, cellH, onHoldEmpty),
    ) { measurables, constraints ->
        val placeables = measurables.map { measurable ->
            val cell = measurable.parentData as CellPlacement
            val size = cell.size.value
            measurable.measure(Constraints.fixed(size.width, size.height)) to cell
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { (placeable, cell) -> placeable.place(cell.position.value) }
        }
    }
}

/**
 * A long press on a cell no item covers reports its root position; one on an item is that item's
 * own affair. The hold is the same as an icon's lift. Nothing is consumed before the press lands,
 * so a swipe that starts on empty space still scrolls or pulls as it did. With no [onHold] (the
 * dock, which has no menu) nothing is watched at all: a hold there must not keep the finger.
 */
@Composable
private fun Modifier.holdsEmptyCells(
    items: List<HomeItem>,
    cellW: Int,
    cellH: Int,
    onHold: ((Point) -> Unit)?,
): Modifier {
    if (onHold == null) return this
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val current = rememberUpdatedState(items)
    val hold = rememberUpdatedState(onHold)
    return onGloballyPositioned { coords = it }
        .pointerInput(cellW, cellH) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val cellX = (down.position.x / cellW).toInt()
                val cellY = (down.position.y / cellH).toInt()
                val covered =
                    current.value.any { item ->
                        cellX in item.x until item.x + item.spanX &&
                            cellY in item.y until item.y + item.spanY
                    }
                val press = if (covered) null else awaitLongPressOrCancellation(down.id)
                if (press != null) {
                    val root = coords?.localToRoot(press.position) ?: press.position
                    hold.value(Point(root.x, root.y))
                    // The finger is the menu's now: no scroll or pull from here on.
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                }
            }
        }
}

/**
 * What the drag means for one cell, derived: a finger's every move changes the drag state, but only
 * a cell whose own answer changes needs to recompose.
 */
private class CellDrag(
    footprint: State<Footprint>,
    settlingHere: State<DragSession?>,
    lifted: State<Boolean>,
    receiving: State<Boolean>,
    resizing: State<Boolean>,
    framed: State<Boolean>,
) {
    val footprint by footprint
    val settlingHere by settlingHere
    val lifted by lifted
    val receiving by receiving

    /** A resize handle is pulling this widget. */
    val resizing by resizing

    /** The resize frame is around this widget, and wants to know where it is. */
    val framed by framed
}

@Composable
private fun rememberCellDrag(
    item: HomeItem,
    drag: DragSession?,
    resize: WidgetResizeSession?,
): CellDrag =
    remember(item, drag, resize) {
        CellDrag(
            footprint =
                derivedStateOf {
                    resize?.previewFor(item.id) ?: drag?.previewFor(item.id) ?: item.footprint
                },
            settlingHere = derivedStateOf { drag?.takeIf { it.settling?.itemId == item.id } },
            lifted = derivedStateOf { item.id == drag?.draggedId },
            receiving = derivedStateOf { item.id == drag?.foldTargetId },
            resizing = derivedStateOf { resize?.previewFor(item.id) != null },
            framed = derivedStateOf { resize?.frames(item.id) == true },
        )
    }
