package com.grayvines.runway.ui.home

import android.app.WallpaperManager
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Slides the system wallpaper across [pager]'s pages when [enabled] is true and there is more than
 * one page, and centres it otherwise. Updated in the draw pass so the wallpaper offset is sent on
 * the same frame the pager draws, without recomposing the screen or waiting for a coroutine turn.
 */
@Composable
internal fun WallpaperParallax(pager: PagerState, enabled: Boolean) {
    val view = LocalView.current
    val wallpaper = remember(view.context) { WallpaperManager.getInstance(view.context) }
    val sent = remember { ParallaxSent() }
    Spacer(
        Modifier.drawBehind {
            val token = view.windowToken
            if (token != null) {
                val count = pager.pageCount
                val step = wallpaperStep(count, enabled)
                val offset =
                    wallpaperOffset(
                        pager.currentPage,
                        pager.currentPageOffsetFraction,
                        count,
                        enabled,
                    )
                if (step != sent.step) {
                    wallpaper.setWallpaperOffsetSteps(step, 1f)
                    sent.step = step
                }
                if (offset != sent.offset) {
                    wallpaper.setWallpaperOffsets(token, offset, CENTER_OFFSET)
                    sent.offset = offset
                }
            }
        }
    )
}

private class ParallaxSent {
    var step: Float = Float.NaN
    var offset: Float = Float.NaN
}

/**
 * The horizontal step size to report to [WallpaperManager.setWallpaperOffsetSteps] for [pageCount]
 * pages when parallax is [enabled]: `1 / (pageCount - 1)` across multiple pages, or `1` when
 * parallax is off or there is only one page.
 */
internal fun wallpaperStep(pageCount: Int, enabled: Boolean): Float =
    if (enabled && pageCount > 1) 1f / (pageCount - 1) else 1f

/**
 * The horizontal wallpaper offset in `[0, 1]` for [page] and [offsetFraction] across [pageCount]
 * pages when parallax is [enabled], or `0.5` (centred) when parallax is off or there is at most one
 * page.
 */
internal fun wallpaperOffset(
    page: Int,
    offsetFraction: Float,
    pageCount: Int,
    enabled: Boolean,
): Float =
    if (enabled && pageCount > 1) {
        ((page + offsetFraction) / (pageCount - 1)).coerceIn(0f, 1f)
    } else {
        CENTER_OFFSET
    }

private const val CENTER_OFFSET = 0.5f

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

/** How long a flip's scroll takes. */
internal const val FLIP_SCROLL_MS = 250
/** How long a flip waits for the pager to learn of a page just added: a few compositions. */
private const val PAGE_COUNT_LAG_MS = 250L
