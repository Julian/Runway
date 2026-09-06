package com.grayvines.runway.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.drag.Bounds

const val DOCK_TAG = "dock"

/** Bottom dock: one full grid row, its own pages, [slots] cells per page. */
@Composable
fun Dock(
    pages: List<HomePage>,
    pagerState: PagerState,
    slots: Int,
    rowHeight: Dp,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem) -> Unit,
    drag: DragSession?,
    onPagePositioned: (page: Int, Bounds) -> Unit,
) {
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxWidth().height(rowHeight).testTag(DOCK_TAG),
    ) { page ->
        val bySlot = pages[page].items.associateBy { it.x }
        Row(
            Modifier.fillMaxSize().onGloballyPositioned { coords ->
                onPagePositioned(page, coords.boundsInRoot().toBounds())
            }
        ) {
            repeat(slots) { slot ->
                Box(Modifier.weight(1f).fillMaxSize()) {
                    bySlot[slot]?.let { item ->
                        ItemCell(
                            item,
                            iconSize = iconSize,
                            labels = labels,
                            onClick = { onLaunch(item) },
                            drag = drag?.handlersFor(item, page, Container.DOCK),
                            lifted = item.id == drag?.draggedId,
                        )
                    }
                }
            }
        }
    }
}
