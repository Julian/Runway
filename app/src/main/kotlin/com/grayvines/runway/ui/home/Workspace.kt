package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
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
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    drag: DragSession?,
    onPagePositioned: (page: Int, Bounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalPager(
        state = pagerState,
        modifier = modifier.fillMaxSize().testTag(WORKSPACE_TAG),
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
                    onPagePositioned(page, coords.boundsInRoot().toBounds())
                },
        )
    }
}

internal fun androidx.compose.ui.geometry.Rect.toBounds() = Bounds(left, top, right, bottom)
