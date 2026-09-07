package com.grayvines.runway.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow

private const val FLIP_SCROLL_MS = 250

/** Reports the page a pager has settled on, to whichever callback is current. */
@Composable
internal fun PagesShown(
    home: PagerState,
    dock: PagerState,
    onHomeShown: (page: Int) -> Unit,
    onDockShown: (page: Int) -> Unit,
) {
    PageShown(home, onHomeShown)
    PageShown(dock, onDockShown)
}

@Composable
private fun PageShown(pager: PagerState, onShown: (page: Int) -> Unit) {
    val shown = rememberUpdatedState(onShown)
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.collect { shown.value(it) } }
}

/** Drives the home pager from outside: HOME returns to page 1, edge dwells flip pages. */
@Composable
internal fun PagerCommands(pager: PagerState, goHome: Flow<Unit>, flipPage: Flow<Int>) {
    LaunchedEffect(goHome) { goHome.collect { pager.animateScrollToPage(0) } }
    PageFlips(pager, flipPage)
}

/**
 * Flips [pager] by each delta on [flipPage]; deltas with no page to go to are ignored. The scroll
 * is shorter than the dwell between ticks, so each page settles before the next flip.
 */
@Composable
internal fun PageFlips(pager: PagerState, flipPage: Flow<Int>) {
    LaunchedEffect(flipPage) {
        flipPage.collect { delta ->
            val next = pager.currentPage + delta
            if (next in 0 until pager.pageCount) {
                pager.animateScrollToPage(next, animationSpec = tween(FLIP_SCROLL_MS))
            }
        }
    }
}
