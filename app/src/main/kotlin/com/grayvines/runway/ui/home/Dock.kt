package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.drag.Bounds

const val DOCK_TAG = "dock"

/** Bottom dock: one full grid row, its own pages, [slots] cells per page. */
@Composable
fun Dock(
    pages: List<HomePage>,
    pagerState: PagerState,
    slots: Int,
    slot: DpSize,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem) -> Unit,
    drag: DragSession?,
    onPagePositioned: (page: Int, Bounds) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth().height(slot.height).testTag(DOCK_TAG),
        // As on home: the dragged cell owns the gesture, so its page must stay composed while
        // the dock flips away from it.
        beyondViewportPageCount = if (drag?.state != null) pages.size else 1,
    ) { page ->
        GridPage(
            items = pages[page].items.filter { it.x < slots },
            cell = slot,
            iconSize = iconSize,
            labels = labels,
            onLaunch = onLaunch,
            drag = drag,
            handlersFor = { item -> drag?.handlersFor(item, page, Container.DOCK) },
            modifier =
                Modifier.onGloballyPositioned { coords ->
                    onPagePositioned(page, coords.boundsInRoot().toBounds())
                },
        )
    }
}
