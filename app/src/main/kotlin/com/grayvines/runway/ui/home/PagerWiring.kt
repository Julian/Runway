package com.grayvines.runway.ui.home

import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Reports the page each pager shows, and whether it has settled there or is still scrolling, to
 * whichever callback is current.
 */
@Composable
internal fun PagesShown(
    home: PagerState,
    dock: PagerState,
    onHomeShown: (page: Int, settled: Boolean) -> Unit,
    onDockShown: (page: Int, settled: Boolean) -> Unit,
) {
    PageShown(home, onHomeShown)
    PageShown(dock, onDockShown)
}

@Composable
private fun PageShown(pager: PagerState, onShown: (page: Int, settled: Boolean) -> Unit) {
    val shown = rememberUpdatedState(onShown)
    LaunchedEffect(pager) {
        snapshotFlow { pager.currentPage to !pager.isScrollInProgress }
            .collect { (page, settled) -> shown.value(page, settled) }
    }
}

/**
 * Drives the home pager from outside: HOME returns to page 1, edge dwells flip pages, and a page
 * just added ([showPage], by index) is scrolled to.
 */
@Composable
internal fun PagerCommands(
    pager: PagerState,
    goHome: Flow<Unit>,
    flipPage: Flow<Int>,
    showPage: Flow<Int>,
) {
    LaunchedEffect(goHome) { goHome.collect { pager.animateScrollToPage(0) } }
    LaunchedEffect(showPage) {
        showPage.collect { page ->
            // The page was just written; the pager learns of it a composition later.
            snapshotFlow { pager.pageCount }.first { it > page }
            pager.animateScrollToPage(page)
        }
    }
    PageFlips(pager, flipPage)
}

/**
 * Flips [pager] by each delta on [flipPage]; deltas with no page to go to are ignored. The scroll
 * is shorter than the dwell between ticks, so each page settles before the next flip.
 *
 * A flip onto a page the dwell has just added can arrive before the pager knows the page: the
 * layout is written and the flip emitted in the same turn, while the pager's count comes from the
 * composed state, one composition behind. Such a flip waits for the count, briefly.
 */
@Composable
internal fun PageFlips(pager: PagerState, flipPage: Flow<Int>) {
    LaunchedEffect(flipPage) {
        flipPage.collect { delta ->
            val next = pager.currentPage + delta
            if (next >= 0 && pager.knows(next)) {
                pager.animateScrollToPage(next, animationSpec = tween(FLIP_SCROLL_MS))
            }
        }
    }
}

private suspend fun PagerState.knows(page: Int): Boolean =
    page < pageCount ||
        withTimeoutOrNull(PAGE_COUNT_LAG_MS) { snapshotFlow { pageCount }.first { it > page } } !=
            null

private const val FLIP_SCROLL_MS = 250
/** How long a flip waits for the pager to learn of a page just added: a few compositions. */
private const val PAGE_COUNT_LAG_MS = 250L
