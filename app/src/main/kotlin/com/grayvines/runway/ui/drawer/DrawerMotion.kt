package com.grayvines.runway.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import com.grayvines.runway.data.settings.DrawerSwipe
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * A pull of this much of the screen height brings the drawer all the way up: it outruns the finger,
 * so even a short pull shows a good part of it.
 */
private const val PULL_FRACTION = 0.35f

/** A pull this far from rest (of its full travel) has made up its mind which way it goes. */
private const val COMMIT = 0.01f

/**
 * How much of the drawer is showing, 0 closed to 1 open, and what a finger does to it. The finger
 * moves it directly; letting go either finishes opening or lets it fall back, so a partial pull
 * shows what a full one would do.
 */
class DrawerMotion(private val scope: CoroutineScope) {
    val revealed = Animatable(0f)

    /**
     * How far a pull the other way, down from a closed drawer, has gone, 0 to 1 of what would open
     * the shade: the home screen gives way by it, so the swipe is seen to register.
     */
    val given = Animatable(0f)
    private var travel = 1f
    private var openAt = 0f
    private var flick = 0f

    /**
     * Where the finger has pulled the drawer to, kept synchronously: the animatable catches up on
     * its own frames, and a quick flick lets go before it has, which used to read as "no pull".
     * Negative is a pull the other way, down from a closed drawer, which is for the shade.
     */
    private var pulled = 0f

    /** Which side of rest this pull committed to: 1 up (drawer), -1 down (shade), 0 not yet. */
    private var way = 0

    /**
     * True from the first move of a pull until it is released. A pull whose gesture is taken over
     * (a long press, a page swipe) never reports letting go; whoever sees the finger lift must
     * release it, or the drawer stays wherever it was.
     */
    var pulling = false
        private set

    /** [swipe] sets the pull (a share of the screen) and the flick (dp/s, scaled by [density]). */
    fun laidOut(heightPx: Float, density: Float, swipe: DrawerSwipe) {
        travel = heightPx * PULL_FRACTION
        openAt = swipe.openAt / PULL_FRACTION
        flick = swipe.flickDpPerSecond * density
    }

    /** The finger moved [dy] pixels (negative is up) with the drawer under it. */
    fun dragBy(dy: Float) {
        if (!pulling) {
            pulling = true
            pulled = revealed.value
            way = 0
        }
        val moved = (pulled - dy / travel).coerceIn(-1f, 1f)
        // One swipe, one direction: past a little way out, it is committed to that side of
        // rest, and coming back can only undo it, never turn into the other action.
        if (way == 0 && abs(moved) > COMMIT) way = if (moved > 0f) 1 else -1
        pulled =
            when (way) {
                1 -> moved.coerceAtLeast(0f)
                -1 -> moved.coerceAtMost(0f)
                else -> moved
            }
        val to = pulled.coerceAtLeast(0f)
        val down = (-pulled / openAt).coerceIn(0f, 1f)
        scope.launch {
            revealed.snapTo(to)
            given.snapTo(down)
        }
    }

    /**
     * The finger let go at [velocity] px/s (negative is up). Asks for the state change if the
     * drawer should end up other than it is; a pull down from a closed drawer that would have
     * opened it the other way asks for the shade instead; otherwise animates back to where it
     * belongs.
     */
    fun release(
        velocity: Float,
        open: Boolean,
        onOpen: () -> Unit,
        onClose: () -> Unit,
        onOpenShade: () -> Unit,
    ) {
        // A flick counts only the way the swipe committed to: flicking back is a change of mind.
        val wantOpen =
            way >= 0 &&
                shouldOpen(if (pulling) pulled else revealed.value, -velocity, openAt, flick)
        val wantShade = pulling && !open && way < 0 && shouldOpen(-pulled, velocity, openAt, flick)
        pulling = false
        when {
            wantOpen && !open -> {
                onOpen()
            }
            !wantOpen && open -> {
                onClose()
            }
            else -> {
                if (wantShade) onOpenShade()
                scope.launch { settle(open) }
            }
        }
    }

    /** Follows the model: open animates the rest of the way in, closed the rest of the way out. */
    suspend fun settle(open: Boolean) {
        scope.launch { given.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
        revealed.animateTo(if (open) 1f else 0f, spring(stiffness = Spring.StiffnessMediumLow))
    }
}

/**
 * [upwardsPxPerSecond] is the release velocity, positive when the finger moves up. A flick faster
 * than [flickPxPerSecond] decides on its own; otherwise past [openAt] (a share of the pull
 * distance) a released drawer opens, below it it falls back.
 */
internal fun shouldOpen(
    revealed: Float,
    upwardsPxPerSecond: Float,
    openAt: Float,
    flickPxPerSecond: Float,
) =
    when {
        upwardsPxPerSecond > flickPxPerSecond -> true
        upwardsPxPerSecond < -flickPxPerSecond -> false
        else -> revealed >= openAt
    }
