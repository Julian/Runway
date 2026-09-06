package com.grayvines.runway.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Past this much showing, a released drawer opens; below it, it falls back. */
private const val OPEN_FRACTION = 0.3f

/** A flick faster than this, in pull distances per second, decides regardless of position. */
private const val FLICK = 1.5f

/** A pull of this much of the screen height brings the drawer all the way up. */
private const val PULL_FRACTION = 0.6f

/**
 * How much of the drawer is showing, 0 closed to 1 open, and what a finger does to it. The finger
 * moves it directly; letting go either finishes opening or lets it fall back, so a partial pull
 * shows what a full one would do.
 */
class DrawerMotion(private val scope: CoroutineScope) {
    val revealed = Animatable(0f)
    private var travel = 1f

    fun laidOut(heightPx: Float) {
        travel = heightPx * PULL_FRACTION
    }

    /** The finger moved [dy] pixels (negative is up) with the drawer under it. */
    fun dragBy(dy: Float) {
        scope.launch { revealed.snapTo((revealed.value - dy / travel).coerceIn(0f, 1f)) }
    }

    /**
     * The finger let go at [velocity] px/s (negative is up). Asks for the state change if the
     * drawer should end up other than it is; otherwise animates back to where it belongs.
     */
    fun release(velocity: Float, open: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
        val wantOpen = shouldOpen(revealed.value, -velocity / travel)
        when {
            wantOpen && !open -> onOpen()
            !wantOpen && open -> onClose()
            else -> scope.launch { settle(open) }
        }
    }

    /** Follows the model: open animates the rest of the way in, closed the rest of the way out. */
    suspend fun settle(open: Boolean) {
        revealed.animateTo(if (open) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow))
    }
}

/** [upwardsPerSecond] is in pull distances per second, positive when the finger moves up. */
internal fun shouldOpen(revealed: Float, upwardsPerSecond: Float): Boolean =
    when {
        upwardsPerSecond > FLICK -> true
        upwardsPerSecond < -FLICK -> false
        else -> revealed >= OPEN_FRACTION
    }
