package com.grayvines.runway.ui.home

import com.grayvines.runway.ui.drawer.shouldOpen

/**
 * One swipe past the first page, measured towards the page that is not there: how far past touch
 * slop the finger has come, and whether letting go runs the swipe's action. The drawer's rules,
 * sideways: past [openAtPx], or a flick faster than [flickPxPerSecond] at any distance, runs it; a
 * finger that has come back more than [reversalPx] from the farthest it got, or is moving back
 * faster than [againstPxPerSecond] as it lets go, has changed its mind.
 */
internal class EdgePull(
    private val openAtPx: Float,
    private val flickPxPerSecond: Float,
    private val reversalPx: Float,
    private val againstPxPerSecond: Float,
) {
    private var pulledPx = 0f
    private var farthestPx = 0f

    /** The finger moved [px] further past the edge; negative, back towards the pages. */
    fun moveBy(px: Float) {
        pulledPx += px
        farthestPx = maxOf(farthestPx, pulledPx)
    }

    /** Whether letting go now, moving at [pxPerSecond] past the edge, runs the action. */
    fun runs(pxPerSecond: Float) =
        farthestPx - pulledPx <= reversalPx &&
            shouldOpen(pulledPx, pxPerSecond, openAtPx, flickPxPerSecond, againstPxPerSecond)
}
