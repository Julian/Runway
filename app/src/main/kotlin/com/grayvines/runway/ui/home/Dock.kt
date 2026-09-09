package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    drag: DragSession?,
    onPagePositioned: (page: Int, Bounds) -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(slot.height)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize().testTag(DOCK_TAG)) {
            page ->
            GridPage(
                items = pages[page].items.filter { it.x < slots },
                cell = slot,
                iconSize = iconSize,
                labels = labels,
                onLaunch = onLaunch,
                drag = drag,
                handlersFor = { item -> drag?.handlersFor(item, page, Container.DOCK) },
                onHoldEmpty = {}, // the dock is for reaching, not for the home menu
                modifier =
                    Modifier.onGloballyPositioned { coords ->
                        onPagePositioned(page, coords.boundsInRoot().toBounds())
                    },
            )
        }
        PageDots(pagerState, Modifier.align(Alignment.TopCenter))
    }
}
