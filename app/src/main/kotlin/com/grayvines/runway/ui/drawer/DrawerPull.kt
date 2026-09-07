package com.grayvines.runway.ui.drawer

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker

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
            Modifier.pointerInput(motion) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (motion.pulling && event.changes.none { it.pressed }) release.value(0f)
                    }
                }
            }
        }
    )
}

private fun Modifier.pullsDrawer(motion: DrawerMotion, onRelease: (velocity: Float) -> Unit) =
    pointerInput(motion) {
        val tracker = VelocityTracker()
        detectVerticalDragGestures(
            onDragStart = { tracker.resetTracking() },
            onDragEnd = { onRelease(tracker.calculateVelocity().y) },
            onDragCancel = { onRelease(0f) },
            onVerticalDrag = { change, dy ->
                tracker.addPosition(change.uptimeMillis, change.position)
                motion.dragBy(dy)
            },
        )
    }
