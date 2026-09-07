package com.grayvines.runway.ui.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangedIgnoreConsumed
import com.grayvines.runway.ui.drag.Point

/**
 * Follows the finger for a drag from the root, so the cell that started it need not survive: a page
 * can be disposed by flipping away from it and the drawer can close, and the drag goes on. The cell
 * only reports the hold and the start; from then on every move, the release, and a cancelled
 * gesture come from here, in root pixels (this box fills the root). It only watches the final pass
 * and consumes nothing, so every other gesture is untouched. Build it once and keep it: a
 * pointer-input block that changes identity restarts mid-gesture.
 */
fun Modifier.tracksDrag(session: () -> DragSession): Modifier =
    pointerInput(Unit) {
        val finger = Finger(session)
        try {
            awaitPointerEventScope {
                while (true) finger.saw(awaitPointerEvent(PointerEventPass.Final))
            }
        } finally {
            // The gesture was taken away (or the node reset) with the finger still down.
            finger.abandon()
        }
    }

/**
 * The first finger down, followed until it lifts; whatever drag is live meanwhile moves with it.
 * Every move and the lift are forwarded whether or not a drag is known to be live: the drag state
 * reaches the screen a frame after the long press, and a hold, move and lift can all arrive in one
 * batch of input, which would otherwise leave the lifted icon with no finger to end it.
 */
private class Finger(private val session: () -> DragSession) {
    private var id: PointerId? = null

    fun saw(event: PointerEvent) {
        val current = id
        if (current == null) {
            id = event.changes.firstOrNull { it.changedToDownIgnoreConsumed() }?.id
            return
        }
        val change = event.changes.firstOrNull { it.id == current }
        if (change == null) {
            id = null
            return
        }
        val drag = session()
        when {
            change.changedToUpIgnoreConsumed() -> {
                drag.end()
                id = null
            }
            change.positionChangedIgnoreConsumed() -> {
                drag.move(Point(change.position.x, change.position.y))
            }
        }
    }

    fun abandon() {
        if (id != null) session().cancel()
    }
}
