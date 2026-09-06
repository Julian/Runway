package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize

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
) {
    Layout(
        content = {
            items.forEach { item ->
                key(item.id) {
                    ItemCell(item, iconSize = iconSize, labels = labels) { onLaunch(item) }
                }
            }
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val cellW = cell.width.roundToPx()
        val cellH = cell.height.roundToPx()
        val placeables = measurables.mapIndexed { i, measurable ->
            val item = items[i]
            measurable.measure(Constraints.fixed(item.spanX * cellW, item.spanY * cellH))
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEachIndexed { i, placeable ->
                placeable.place(items[i].x * cellW, items[i].y * cellH)
            }
        }
    }
}
