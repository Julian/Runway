package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.widgets.WidgetResizeSession

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
    onHoldEmpty: (Point) -> Unit,
    modifier: Modifier = Modifier,
    resize: WidgetResizeSession? = null,
) {
    Box(modifier.fillMaxSize()) {
        HorizontalPager(
            state = pagerState,
            // A finger carrying an icon is not swiping pages: were the pager still listening, it
            // would take the finger's lift as the end of a swipe of its own and snap back,
            // cutting short a flip the drop had asked for.
            userScrollEnabled = drag?.state == null,
            modifier = Modifier.fillMaxSize().testTag(WORKSPACE_TAG),
        ) { page ->
            GridPage(
                items =
                    pages[page].items.filter {
                        it.x + it.spanX <= columns && it.y + it.spanY <= rows
                    },
                cell = cell,
                iconSize = iconSize,
                labels = labels,
                onLaunch = onLaunch,
                drag = drag,
                handlersFor = { item -> drag?.handlersFor(item, page) },
                onHoldEmpty = onHoldEmpty,
                resize = resize,
                landing = { drag?.plannedHomeFootprint(page) },
                modifier =
                    Modifier.onGloballyPositioned { coords ->
                        onPagePositioned(page, coords.boundsInRoot().toBounds())
                    },
            )
        }
        PageDots(pagerState, Modifier.align(Alignment.BottomCenter).padding(bottom = DOTS_INSET))
    }
}

/** The dots sit just inside the pages' bottom edge, over the last row's gap. */
private val DOTS_INSET = 6.dp

internal fun androidx.compose.ui.geometry.Rect.toBounds() = Bounds(left, top, right, bottom)
