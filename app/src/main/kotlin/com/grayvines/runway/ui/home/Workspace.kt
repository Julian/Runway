package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.widgets.WidgetResizeSession

const val WORKSPACE_TAG = "workspace"

/**
 * Home pages, swiped horizontally; a swipe right on the first page calls [onSwipePastFirstPage].
 */
@Composable
fun Workspace(
    pages: List<HomePage>,
    pagerState: PagerState,
    cell: DpSize,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    drag: DragSession?,
    onPagePositioned: (page: Int, Bounds) -> Unit,
    onHoldEmpty: (Point) -> Unit,
    /** How far or fast a swipe past the first page must go to count. */
    swipeSensitivity: DrawerSwipe,
    onSwipePastFirstPage: () -> Unit,
    modifier: Modifier = Modifier,
    resize: WidgetResizeSession? = null,
) {
    Box(
        modifier
            .fillMaxSize()
            .swipesPastFirstPage(pagerState, swipeSensitivity, onSwipePastFirstPage)
    ) {
        PagedGrid(
            pages = pages,
            pagerState = pagerState,
            cell = cell,
            iconSize = iconSize,
            labels = labels,
            onLaunch = onLaunch,
            drag = drag,
            handlersFor = { page, item -> drag?.handlersFor(item, page) },
            onPagePositioned = onPagePositioned,
            landing = { page -> drag?.plannedHomeFootprint(page) },
            modifier = Modifier.testTag(WORKSPACE_TAG),
            onHoldEmpty = onHoldEmpty,
            resize = resize,
        )
        PageDots(pagerState, Modifier.align(Alignment.BottomCenter).padding(bottom = DOTS_INSET))
    }
}

/** The dots sit just inside the pages' bottom edge, over the last row's gap. */
private val DOTS_INSET = 6.dp

internal fun androidx.compose.ui.geometry.Rect.toBounds() = Bounds(left, top, right, bottom)
