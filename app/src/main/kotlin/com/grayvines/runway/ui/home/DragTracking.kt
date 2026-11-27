package com.grayvines.runway.ui.home

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
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
 * The finger carrying the live drag, followed from the moment a cell names it
 * ([DragSession.finger]) until it lifts or is cancelled. Reading [DragSession.finger] directly
 * handles a hold, move, and lift arriving in one input batch before [DragSession.active]
 * recomposes, while ignoring unrelated taps or hover/scroll events from other input devices.
 */
private class Finger(private val session: () -> DragSession) {
    private var id: PointerId? = null
    private var pointerType: PointerType? = null

    fun saw(event: PointerEvent) {
        val drag = session()
        val activeId = drag.finger
        if (activeId == null) {
            clear()
            return
        }
        if (id != activeId) {
            id = activeId
            pointerType = null
        }
        val change = event.changes.firstOrNull { it.id == activeId }
        if (change == null) {
            if (isSameStreamMissing(event)) {
                drag.cancel()
                clear()
            }
            return
        }
        pointerType = change.type
        when {
            change.changedToUpIgnoreConsumed() -> {
                drag.end()
                clear()
            }
            !change.pressed -> {
                drag.cancel()
                clear()
            }
            change.positionChangedIgnoreConsumed() -> {
                drag.move(Point(change.position.x, change.position.y))
            }
        }
    }

    fun abandon() {
        val current = id ?: return
        clear()
        val drag = session()
        if (drag.finger == current) drag.cancel()
    }

    private fun isSameStreamMissing(event: PointerEvent): Boolean {
        val ignored =
            event.type == PointerEventType.Enter ||
                event.type == PointerEventType.Exit ||
                event.type == PointerEventType.Scroll
        if (ignored) return false
        val trackedType = pointerType
        return if (trackedType != null) {
            event.changes.any { it.type == trackedType }
        } else {
            event.type == PointerEventType.Release
        }
    }

    private fun clear() {
        id = null
        pointerType = null
    }
}
