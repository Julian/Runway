package com.grayvines.runway.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** How long the drawer takes to catch up with the finger at the start of a pull. */
private const val CATCH_UP_MS = 120

/**
 * A finger that comes back this far (dp) from the farthest it pulled has changed its mind: letting
 * go then cancels, however far the pull had got.
 */
private const val REVERSAL_DP = 24f

/**
 * How much of the drawer is showing, 0 closed to 1 open, and what a finger does to it. The finger
 * moves it directly; letting go either finishes opening or lets it fall back, so a partial pull
 * shows what a full one would do.
 */
class DrawerMotion(private val scope: CoroutineScope) {
    /** Where the drawer rests or is animating to; while a finger pulls, see [shown]. */
    val revealed = Animatable(0f)

    /**
     * How much of the drawer is showing, 0 to 1, for drawing. Under a finger the drawer's top edge
     * catches up with the finger over [CATCH_UP_MS] and then moves with it, one to one; otherwise
     * it is [revealed].
     */
    val shown: State<Float> = derivedStateOf {
        if (pulling && heightPx > 0f) {
            ((basePx + catchUpPx * caughtUp.value + pulledPx) / heightPx).coerceIn(0f, 1f)
        } else {
            revealed.value
        }
    }

    private val caughtUp = Animatable(0f)
    private var heightPx = 0f

    /**
     * Of the screen: the drawer showing when the pull began, and what it must gain to be at the
     * finger.
     */
    private var basePx = 0f
    private var catchUpPx = 0f

    /** How far up the finger has pulled since the pull began (negative: down). */
    private var pulledPx by mutableFloatStateOf(0f)

    /**
     * The farthest [pulledPx] has been in the committed direction, and what counts as coming back.
     */
    private var farthestPx = 0f
    private var reversalPx = 0f

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
    var pulling by mutableStateOf(false)
        private set

    /** [swipe] sets the pull (a share of the screen) and the flick (dp/s, scaled by [density]). */
    fun laidOut(heightPx: Float, density: Float, swipe: DrawerSwipe) {
        this.heightPx = heightPx
        travel = heightPx * PULL_FRACTION
        openAt = swipe.openAt / PULL_FRACTION
        flick = swipe.flickDpPerSecond * density
        reversalPx = REVERSAL_DP * density
    }

    /**
     * A finger has begun a pull at [fingerY] (root px), or somewhere unknown ([fingerY] null, as
     * when the open drawer's own list hands its scroll over). From a closed drawer, its top edge
     * sets off to meet the finger.
     */
    fun startPull(fingerY: Float?) {
        pulling = true
        pulled = revealed.value
        way = 0
        pulledPx = 0f
        farthestPx = 0f
        basePx = revealed.value * heightPx
        catchUpPx = fingerY?.let { (heightPx - it - basePx).coerceAtLeast(0f) } ?: 0f
        scope.launch {
            caughtUp.snapTo(0f)
            caughtUp.animateTo(1f, tween(CATCH_UP_MS))
        }
    }

    /** The finger moved [dy] pixels (negative is up) with the drawer under it. */
    fun dragBy(dy: Float) {
        if (!pulling) startPull(null)
        pulledPx -= dy
        farthestPx =
            when (way) {
                1 -> maxOf(farthestPx, pulledPx)
                -1 -> minOf(farthestPx, pulledPx)
                else -> pulledPx
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
        val down = (-pulled / openAt).coerceIn(0f, 1f)
        scope.launch { given.snapTo(down) }
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
        val wantOpen = wantsOpen(velocity)
        val wantShade =
            pulling &&
                !open &&
                way < 0 &&
                !reversed() &&
                shouldOpen(-pulled, velocity, openAt, flick)
        // Whatever comes next animates from where the drawer is drawn now.
        val at = shown.value
        scope.launch { revealed.snapTo(at) }
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

    /**
     * A flick counts only the way the swipe committed to, and a finger that came back from its
     * farthest point has changed its mind: either way, letting go cancels.
     */
    private fun reversed() = pulling && abs(farthestPx - pulledPx) > reversalPx

    private fun wantsOpen(velocity: Float) =
        way >= 0 &&
            !reversed() &&
            shouldOpen(if (pulling) pulled else revealed.value, -velocity, openAt, flick)

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
