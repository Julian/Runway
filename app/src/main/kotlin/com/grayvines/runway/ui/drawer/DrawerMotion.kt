package com.grayvines.runway.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.grayvines.runway.data.settings.DrawerSwipe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
    private var openAt = 0f
    private var flick = 0f

    /**
     * Where the finger has pulled the drawer to, kept synchronously: the animatable catches up on
     * its own frames, and a quick flick lets go before it has, which used to read as "no pull".
     */
    private var pulled = 0f
    private var pulling = false

    /** [swipe] sets the pull and the flick that open; both are given as shares of the screen. */
    fun laidOut(heightPx: Float, swipe: DrawerSwipe) {
        travel = heightPx * PULL_FRACTION
        openAt = swipe.openAt / PULL_FRACTION
        flick = swipe.flick / PULL_FRACTION
    }

    /** The finger moved [dy] pixels (negative is up) with the drawer under it. */
    fun dragBy(dy: Float) {
        if (!pulling) {
            pulling = true
            pulled = revealed.value
        }
        pulled = (pulled - dy / travel).coerceIn(0f, 1f)
        val to = pulled
        scope.launch { revealed.snapTo(to) }
    }

    /**
     * The finger let go at [velocity] px/s (negative is up). Asks for the state change if the
     * drawer should end up other than it is; otherwise animates back to where it belongs.
     */
    fun release(velocity: Float, open: Boolean, onOpen: () -> Unit, onClose: () -> Unit) {
        val wantOpen =
            shouldOpen(if (pulling) pulled else revealed.value, -velocity / travel, openAt, flick)
        pulling = false
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

/**
 * [upwardsPerSecond] is in pull distances per second, positive when the finger moves up. A flick
 * faster than [flick] decides on its own; otherwise past [openAt] (a share of the pull distance) a
 * released drawer opens, below it it falls back.
 */
internal fun shouldOpen(revealed: Float, upwardsPerSecond: Float, openAt: Float, flick: Float) =
    when {
        upwardsPerSecond > flick -> true
        upwardsPerSecond < -flick -> false
        else -> revealed >= openAt
    }
