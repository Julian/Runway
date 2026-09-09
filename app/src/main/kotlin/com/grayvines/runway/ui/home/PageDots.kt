package com.grayvines.runway.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

const val PAGE_DOTS_TAG = "page-dots"

private val DOT = 6.dp
private val GAP = 6.dp
private const val CURRENT_ALPHA = 0.9f
private const val OTHER_ALPHA = 0.4f

/** How long the dots stay after the pages stop moving. */
private const val LINGER_MS = 900L

/**
 * One dot per page of [pager], the current one bright. Shown while the pages move and for a moment
 * after, gone otherwise: the wallpaper is the point of the home screen, not the furniture. A single
 * page shows nothing. Hidden means out of the tree, not merely transparent.
 */
@Composable
fun PageDots(pager: PagerState, modifier: Modifier = Modifier) {
    val moving = pager.isScrollInProgress
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(moving) {
        if (moving) {
            shown = true
        } else {
            delay(LINGER_MS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown && pager.pageCount > 1) 1f else 0f, label = "dots")
    if (alpha == 0f) return
    Row(
        modifier.graphicsLayer { this.alpha = alpha }.testTag(PAGE_DOTS_TAG),
        horizontalArrangement = Arrangement.spacedBy(GAP),
    ) {
        repeat(pager.pageCount) { page ->
            val ink = if (page == pager.currentPage) CURRENT_ALPHA else OTHER_ALPHA
            Box(Modifier.size(DOT).background(Color.White.copy(alpha = ink), CircleShape))
        }
    }
}
