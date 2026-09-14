package com.grayvines.runway.ui.home

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.ui.drawer.AGAINST_DP_PER_SECOND
import com.grayvines.runway.ui.drawer.REVERSAL_DP
import kotlin.math.abs
import kotlin.math.sign

/**
 * Swiping right from the first page, where there is no page to go to, calls [onSwipe] as the finger
 * lets go, if the swipe went far or fast enough by [sensitivity]'s measure: the drawer's distance
 * (a share of the screen's height) and flick, sideways. Right is towards the first page's side, so
 * left in a right-to-left layout.
 *
 * It only watches: the pager keeps the swipe, and stretches at its edge under the finger. What
 * counts is what the pager would take for its own: a swipe sideways past touch slop before it goes
 * as far vertically (the drawer's, or a widget's) and before a hold (the menu's, or a lifted
 * icon's). A swipe that heads into the pages first is theirs however it ends. Built once: a rebuilt
 * pointer-input block restarts mid-gesture.
 */
@Composable
internal fun Modifier.swipesPastFirstPage(
    pager: PagerState,
    sensitivity: DrawerSwipe,
    onSwipe: () -> Unit,
): Modifier {
    val swiped = rememberUpdatedState(onSwipe)
    val swipe = rememberUpdatedState(sensitivity)
    val screenHeight = rememberUpdatedState(LocalWindowInfo.current.containerSize.height)
    val direction = rememberUpdatedState(LocalLayoutDirection.current)
    return this.then(
        remember(pager) {
            Modifier.pointerInput(pager) {
                awaitEachGesture {
                    val outwards = if (direction.value == LayoutDirection.Ltr) 1f else -1f
                    val pull = edgePull(swipe.value, screenHeight.value)
                    if (awaitSwipePastFirstPage(pager, outwards, pull)) swiped.value()
                }
            }
        }
    )
}

/** The rules for a swipe measured by [swipe] on a screen [screenHeightPx] tall. */
private fun Density.edgePull(swipe: DrawerSwipe, screenHeightPx: Int) =
    EdgePull(
        openAtPx = swipe.openAt * screenHeightPx,
        flickPxPerSecond = swipe.flickDpPerSecond * density,
        reversalPx = REVERSAL_DP * density,
        againstPxPerSecond = AGAINST_DP_PER_SECOND * density,
    )

/**
 * Follows one gesture: whether it was a swipe [outwards] (1 right, -1 left) from [pager]'s first
 * page, at rest there, that let go as [pull] says runs the action.
 */
private suspend fun AwaitPointerEventScope.awaitSwipePastFirstPage(
    pager: PagerState,
    outwards: Float,
    pull: EdgePull,
): Boolean {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    // A finger still inside slop by then has held whatever it is on.
    val hold = maxOf(LIFT_HOLD_MS, viewConfiguration.longPressTimeoutMillis)
    val start =
        if (pager.canScrollBackward) {
            null
        } else {
            withTimeoutOrNull(hold) { awaitSidewaysSlop(down.id) }
        }
    val velocity = start?.takeIf { it.way == outwards }?.let { followPull(it, pull, outwards) }
    return velocity != null && pull.runs(velocity * outwards)
}

/**
 * The move on which a finger crossed touch slop sideways, which [way] it went (1 right, -1 left),
 * and how far past slop it got on that move (px, positive right).
 */
private class SidewaysStart(val change: PointerInputChange, val way: Float, val overSlopX: Float)

/**
 * Follows [pointer] until it has moved past touch slop. Null if it went as far vertically first, or
 * lifted before either. The initial pass, so that what the pages do with the moves (the pager
 * consumes them) changes nothing here.
 */
private suspend fun AwaitPointerEventScope.awaitSidewaysSlop(pointer: PointerId): SidewaysStart? {
    val slop = viewConfiguration.touchSlop
    var moved = Offset.Zero
    var change: PointerInputChange?
    do {
        change =
            awaitPointerEvent(PointerEventPass.Initial)
                .changes
                .firstOrNull { it.id == pointer }
                ?.takeIf { it.pressed }
        change?.let { moved += it.positionChangeIgnoreConsumed() }
    } while (change != null && abs(moved.x) < slop && abs(moved.y) < slop)
    return change
        ?.takeIf { abs(moved.x) >= slop }
        ?.let { SidewaysStart(it, moved.x.sign, moved.x - slop * moved.x.sign) }
}

/**
 * Follows a swipe from [start] into [pull] until the finger lets go: its velocity then (px/s,
 * positive right), or null if the gesture was taken away. A lift the system took back (a back
 * gesture from the screen's edge, pilfered) arrives already consumed, and lets go of nothing.
 */
private suspend fun AwaitPointerEventScope.followPull(
    start: SidewaysStart,
    pull: EdgePull,
    outwards: Float,
): Float? {
    val tracker = VelocityTracker()
    tracker.addPosition(start.change.uptimeMillis, start.change.position)
    pull.moveBy(start.overSlopX * outwards)
    while (true) {
        val change =
            awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull {
                it.id == start.change.id
            } ?: return null
        if (!change.pressed) {
            return if (change.isConsumed) null else tracker.calculateVelocity().x
        }
        tracker.addPosition(change.uptimeMillis, change.position)
        pull.moveBy(change.positionChangeIgnoreConsumed().x * outwards)
    }
}
