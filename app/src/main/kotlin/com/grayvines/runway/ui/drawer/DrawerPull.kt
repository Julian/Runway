package com.grayvines.runway.ui.drawer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Built once: the pull recomposes every frame, and a rebuilt pointer-input modifier restarts its
 * gesture, which threw away every move after the first frame.
 */
@Composable
fun Modifier.drawerPull(
    motion: DrawerMotion,
    onRelease: (Float) -> Unit,
    /** Whether a finger landing at this root point belongs to a widget, which keeps it. */
    startsOnWidget: (Offset) -> Boolean = { false },
): Modifier {
    val release = rememberUpdatedState(onRelease)
    val ignores = rememberUpdatedState(startsOnWidget)
    val coords = remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this.onGloballyPositioned { coords.value = it }
        .then(
            remember(motion) {
                Modifier.pullsDrawer(
                    motion,
                    rootOf = { local -> coords.value?.localToRoot(local) },
                    startsOnWidget = { ignores.value(it) },
                ) {
                    release.value(it)
                }
            }
        )
}

/**
 * The safety net under every pull: whenever the last finger lifts anywhere on the screen and the
 * drawer is still being pulled, that pull is released. The gestures that pull normally release
 * themselves; one taken over by another gesture (a long press, a page swipe, a scroll cut short)
 * does not, and without this the drawer would stay part way.
 */
@Composable
fun Modifier.releasesAbandonedPull(motion: DrawerMotion, onRelease: (Float) -> Unit): Modifier {
    val release = rememberUpdatedState(onRelease)
    return this.then(
        remember(motion) {
            Modifier.pointerInput(motion) { releaseAbandonedPulls(motion) { release.value(0f) } }
        }
    )
}

/**
 * A vertical drag pulls the drawer (or, downward, the shade) unless it began on a widget: a
 * widget's vertical drags are its own (a list in it scrolls), and Compose would otherwise claim
 * them at touch slop, before the widget's view has had a chance to ask for them.
 */
private fun Modifier.pullsDrawer(
    motion: DrawerMotion,
    rootOf: (local: Offset) -> Offset?,
    startsOnWidget: (root: Offset) -> Boolean,
    onRelease: (velocity: Float) -> Unit,
) =
    pointerInput(motion) {
        val tracker = VelocityTracker()
        try {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val ours = rootOf(down.position)?.let(startsOnWidget) != true
                var overSlop = 0f
                val drag =
                    if (ours) {
                        awaitVerticalTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            overSlop = over
                        }
                    } else {
                        null
                    }
                if (drag != null) {
                    tracker.resetTracking()
                    // The first move carries the slop the finger crossed unnoticed; the pull
                    // begins where the finger was before it, so that after this move the
                    // drawer's edge is exactly under the finger.
                    motion.startPull(rootOf(drag.position - Offset(0f, overSlop))?.y)
                    tracker.addPosition(drag.uptimeMillis, drag.position)
                    motion.dragBy(overSlop)
                    val ended =
                        verticalDrag(drag.id) { change ->
                            tracker.addPosition(change.uptimeMillis, change.position)
                            motion.dragBy(change.positionChange().y)
                            change.consume()
                        }
                    onRelease(if (ended) tracker.calculateVelocity().y else 0f)
                }
            }
        } finally {
            // The system took the touch away (gesture navigation, a call) mid-pull.
            if (motion.pulling) onRelease(0f)
        }
    }

private suspend fun PointerInputScope.releaseAbandonedPulls(
    motion: DrawerMotion,
    release: () -> Unit,
) = coroutineScope {
    var pending: Job? = null
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Final)
            if (motion.pulling && event.changes.none { it.pressed }) {
                // A list's fling reports letting go a moment after the lift, with the real
                // velocity: give it that moment before releasing without one.
                pending?.cancel()
                pending = launch {
                    delay(FLING_GRACE_MS)
                    if (motion.pulling) release()
                }
            }
        }
    }
}

/** How long a lift waits for a list fling to report itself before the net releases the pull. */
private const val FLING_GRACE_MS = 50L
