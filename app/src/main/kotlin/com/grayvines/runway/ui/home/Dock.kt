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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.Point

const val DOCK_TAG = "dock"

/**
 * Bottom dock: one full grid row, its own pages, [slots] cells per page, each a [slot] wide. A
 * lifted icon is drawn in a home [cell], so its grab is translated from the slot to that.
 */
@Composable
fun Dock(
    pages: List<HomePage>,
    pagerState: PagerState,
    slots: Int,
    slot: DpSize,
    cell: DpSize,
    iconSize: Dp,
    labels: Boolean,
    onLaunch: (HomeItem, cell: Bounds) -> Unit,
    drag: DragSession?,
    onPagePositioned: (page: Int, Bounds) -> Unit,
) {
    // The grab is measured within the slot; the overlay's box is a cell centred on the same icon,
    // so the grab within it is nearer the middle by half the difference in width.
    val grabShift = with(LocalDensity.current) { ((slot.width - cell.width) / 2).toPx() }
    Box(Modifier.fillMaxWidth().height(slot.height)) {
        HorizontalPager(
            state = pagerState,
            userScrollEnabled = drag?.state == null, // as on the home pages
            modifier = Modifier.fillMaxSize().testTag(DOCK_TAG),
        ) { page ->
            GridPage(
                items = pages[page].items.filter { it.x < slots },
                cell = slot,
                iconSize = iconSize,
                labels = labels,
                onLaunch = onLaunch,
                drag = drag,
                handlersFor = { item ->
                    drag?.handlersFor(item, page, Container.DOCK)?.grabbedShiftedBy(grabShift)
                },
                onHoldEmpty = null, // the dock is for reaching, not for the home menu
                landing = { drag?.plannedDockFootprint(page) },
                modifier =
                    Modifier.onGloballyPositioned { coords ->
                        onPagePositioned(page, coords.boundsInRoot().toBounds())
                    },
            )
        }
        PageDots(pagerState, Modifier.align(Alignment.TopCenter))
    }
}

/** These handlers with the grab point [dx] px further left. */
private fun DragHandlers.grabbedShiftedBy(dx: Float) =
    DragHandlers(
        onHold = onHold,
        onStart = { pointer, grab -> onStart(pointer, Point(grab.x - dx, grab.y)) },
        onFinger = onFinger,
    )
