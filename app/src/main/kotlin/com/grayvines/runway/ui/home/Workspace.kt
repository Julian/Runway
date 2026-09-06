package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.ui.drag.Bounds

const val WORKSPACE_TAG = "workspace"

/** Home pages, swiped horizontally. */
@Composable
fun Workspace(
    pages: List<HomePage>,
    pagerState: PagerState,
    columns: Int,
    rows: Int,
    cell: DpSize,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem) -> Unit,
    drag: DragSession?,
    onPagePositioned: (page: Int, Bounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().testTag(WORKSPACE_TAG),
        beyondViewportPageCount = 1,
    ) { page ->
        GridPage(
            items =
                pages[page].items.filter { it.x + it.spanX <= columns && it.y + it.spanY <= rows },
            cell = cell,
            iconSize = iconSize,
            labels = labels,
            onLaunch = onLaunch,
            drag = drag,
            handlersFor = { item -> drag?.handlersFor(item, page) },
            modifier =
                Modifier.onGloballyPositioned { coords ->
                    if (page == pagerState.currentPage) {
                        onPagePositioned(page, coords.boundsInRoot().toBounds())
                    }
                },
        )
    }
}

/** Places each item at its cell; items are sized by their span. */
@Composable
private fun GridPage(
    items: List<HomeItem>,
    cell: DpSize,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem) -> Unit,
    drag: DragSession?,
    handlersFor: (HomeItem) -> DragHandlers?,
    modifier: Modifier = Modifier,
) {
    val placedAt: (HomeItem) -> Footprint = { drag?.previewFor(it.id) ?: it.footprint }
    Layout(
        content = {
            items.forEach { item ->
                key(item.id) {
                    ItemCell(
                        item,
                        iconSize = iconSize,
                        labels = labels,
                        onClick = { onLaunch(item) },
                        drag = handlersFor(item),
                        lifted = item.id == drag?.draggedId,
                    )
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val cellW = cell.width.roundToPx()
        val cellH = cell.height.roundToPx()
        val placeables = measurables.mapIndexed { i, measurable ->
            val f = placedAt(items[i])
            measurable.measure(Constraints.fixed(f.width * cellW, f.height * cellH))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                val f = placedAt(items[i])
                placeable.place(f.x * cellW, f.y * cellH)
            }
        }
    }
}

internal fun androidx.compose.ui.geometry.Rect.toBounds() = Bounds(left, top, right, bottom)
