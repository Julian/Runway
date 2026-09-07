package com.grayvines.runway.ui.drawer

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Built once: the pull recomposes every frame, and a rebuilt pointer-input modifier restarts its
 * gesture, which threw away every move after the first frame.
 */
@Composable
fun Modifier.drawerPull(motion: DrawerMotion, onRelease: (Float) -> Unit): Modifier {
    val release = rememberUpdatedState(onRelease)
    return this.then(remember(motion) { Modifier.pullsDrawer(motion) { release.value(it) } })
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

private fun Modifier.pullsDrawer(motion: DrawerMotion, onRelease: (velocity: Float) -> Unit) =
    pointerInput(motion) {
        val tracker = VelocityTracker()
        try {
            detectVerticalDragGestures(
                onDragStart = { tracker.resetTracking() },
                onDragEnd = { onRelease(tracker.calculateVelocity().y) },
                onDragCancel = { onRelease(0f) },
                onVerticalDrag = { change, dy ->
                    tracker.addPosition(change.uptimeMillis, change.position)
                    motion.dragBy(dy)
                },
            )
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
