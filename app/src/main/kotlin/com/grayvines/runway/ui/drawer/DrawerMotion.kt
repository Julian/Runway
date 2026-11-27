package com.grayvines.runway.ui.drawer

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.grayvines.runway.data.settings.DrawerSwipe
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * A pull of this much of the screen height brings the drawer all the way up: it outruns the finger,
 * so even a short pull shows a good part of it.
 */
private const val PULL_FRACTION = 0.35f

/** A pull this far from rest (of its full travel) has made up its mind which way it goes. */
private const val COMMIT = 0.01f

/** Lower bound for [DrawerMotion.openAt] so division never produces NaN or Infinity. */
private const val MIN_OPEN_AT = 0.001f

/** How long the drawer takes to catch up with the finger at the start of a pull. */
private const val CATCH_UP_MS = 120

/**
 * A finger that comes back this far (dp) from the farthest it pulled has changed its mind: letting
 * go then cancels, however far the pull had got.
 */
internal const val REVERSAL_DP = 8f

/**
 * A finger still moving against the pull when it lets go (dp/s), however slowly, has changed its
 * mind; below this is the jitter of a finger holding still.
 */
internal const val AGAINST_DP_PER_SECOND = 30f

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
        val held = handoff
        when {
            pulling && heightPx > 0f ->
                // Only a swipe committed upward moves the drawer; downward, or not yet decided, it
                // stays exactly where it was, so a pull towards the shade never lifts it.
                if (way == 1) {
                    ((basePx + catchUpPx * caughtUp.value + pulledPx) / heightPx).coerceIn(0f, 1f)
                } else {
                    basePx / heightPx
                }
            held != null -> held
            else -> revealed.value
        }
    }

    private val caughtUp = Animatable(0f)
    private var heightPx by mutableFloatStateOf(0f)

    /**
     * Held visual position between a synchronous release/snap and [revealed] catching up, so
     * [shown] never flashes back to an outdated [revealed] value for a frame.
     */
    private var handoff by mutableStateOf<Float?>(null)
    private var catchUpJob: Job? = null
    private var givenJob: Job? = null
    private var transitionJob: Job? = null
    private var lastGiven = 0f

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

    /**
     * Which way the finger set off, 1 up or -1 down, fixed as the pull commits; the farthest point
     * is measured that way. Not [way]: a pull down inside an open drawer heads down while staying
     * on the drawer's side of rest.
     */
    private var heading = 0
    private var reversalPx = 0f
    private var against = 0f

    /**
     * How far a pull the other way, down from a closed drawer, has gone, 0 to 1 of what would open
     * the shade: the home screen gives way by it, so the swipe is seen to register.
     */
    val given = Animatable(0f)
    private var travel = 1f
    private var openAt = 1f
    private var flick = 0f

    /**
     * Where the finger has pulled the drawer to, kept synchronously: the animatable catches up on
     * its own frames, and a quick flick lets go before it has, which used to read as "no pull".
     * Negative is a pull the other way, down from a closed drawer, which is for the shade.
     */
    private var pulled = 0f

    /**
     * Which side of rest this pull committed to: 1 up (drawer), -1 down (shade), 0 not yet. State,
     * not a plain field: [shown] branches on it, and a derived value only recomputes when state it
     * read changes, so a plain field would leave the drawer drawn at rest for the whole pull.
     */
    private var way by mutableIntStateOf(0)

    /**
     * True from the first move of a pull until it is released. A pull whose gesture is taken over
     * (a long press, a page swipe) never reports letting go; whoever sees the finger lift must
     * release it, or the drawer stays wherever it was.
     */
    var pulling by mutableStateOf(false)
        private set

    /** [swipe] sets the pull (a share of the screen) and the flick (dp/s, scaled by [density]). */
    fun laidOut(heightPx: Float, density: Float, swipe: DrawerSwipe) {
        this.heightPx = heightPx.coerceAtLeast(0f)
        travel = (this.heightPx * PULL_FRACTION).coerceAtLeast(1f)
        openAt = (swipe.openAt / PULL_FRACTION).coerceAtLeast(MIN_OPEN_AT)
        flick = swipe.flickDpPerSecond * density
        reversalPx = REVERSAL_DP * density
        against = AGAINST_DP_PER_SECOND * density
    }

    /**
     * A finger has begun a pull at [fingerY] (root px), or somewhere unknown ([fingerY] null, as
     * when the open drawer's own list hands its scroll over). From a closed drawer, its top edge
     * sets off to meet the finger.
     */
    fun startPull(fingerY: Float?) {
        val start = handoff ?: revealed.value
        transitionJob?.cancel()
        catchUpJob?.cancel()
        givenJob?.cancel()
        handoff = null
        pulling = true
        pulled = start
        lastGiven = given.value
        way = 0
        heading = 0
        pulledPx = 0f
        farthestPx = 0f
        basePx = start * heightPx
        catchUpPx = fingerY?.let { (heightPx - it - basePx).coerceAtLeast(0f) } ?: 0f
        transitionJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { revealed.stop() }
        catchUpJob = scope.launch(start = CoroutineStart.UNDISPATCHED) { caughtUp.snapTo(0f) }
    }

    /** The finger moved [dy] pixels (negative is up) with the drawer under it. */
    fun dragBy(dy: Float) {
        if (!pulling) startPull(null)
        pulledPx -= dy
        farthestPx =
            when (heading) {
                1 -> maxOf(farthestPx, pulledPx)
                -1 -> minOf(farthestPx, pulledPx)
                else -> pulledPx
            }
        val moved = (pulled - dy / travel).coerceIn(-1f, 1f)
        // One swipe, one direction: past a little way out, it is committed to that side of
        // rest, and coming back can only undo it, never turn into the other action.
        if (way == 0 && abs(moved) > COMMIT) {
            way = if (moved > 0f) 1 else -1
            heading = if (pulledPx > 0f) 1 else -1
            // Committed upward: now the drawer's top edge sets off to meet the finger.
            if (way == 1) {
                catchUpJob?.cancel()
                catchUpJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        caughtUp.snapTo(0f)
                        caughtUp.animateTo(1f, tween(CATCH_UP_MS))
                    }
            }
        }
        pulled =
            when (way) {
                1 -> moved.coerceAtLeast(0f)
                -1 -> moved.coerceAtMost(0f)
                else -> moved
            }
        val down = (-pulled / openAt).coerceIn(0f, 1f)
        if (down != lastGiven) {
            lastGiven = down
            givenJob?.cancel()
            givenJob = scope.launch { given.snapTo(down) }
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
        if (!pulling) return
        // An open drawer pulled down closes by the mirror of the rule that opens it, unless the
        // finger changed its mind on the way: then it stays.
        val wantOpen = if (open) reversed() || !closes(velocity) else wantsOpen(velocity)
        val wantShade =
            !open && way < 0 && !reversed() && shouldOpen(-pulled, velocity, openAt, flick, against)
        // Whatever comes next animates from where the drawer is drawn now.
        val at = shown.value
        handoff = if (wantShade) 0f else at
        cancelPull()
        transitionJob?.cancel()
        givenJob?.cancel()
        when {
            wantOpen && !open -> {
                transitionJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        revealed.snapTo(at)
                        handoff = null
                    }
                onOpen()
            }
            !wantOpen && open -> {
                transitionJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        revealed.snapTo(at)
                        handoff = null
                    }
                onClose()
            }
            wantShade -> {
                onOpenShade()
                // The shade is about to cover everything: snap back rather than animate under it.
                lastGiven = 0f
                transitionJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        given.snapTo(0f)
                        revealed.snapTo(0f)
                        handoff = null
                    }
            }
            else -> {
                transitionJob =
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        revealed.snapTo(at)
                        handoff = null
                        animateToRest(open)
                    }
            }
        }
    }

    /**
     * A flick counts only the way the swipe committed to, and a finger that came back from its
     * farthest point has changed its mind: either way, letting go cancels.
     */
    private fun reversed() = pulling && abs(farthestPx - pulledPx) > reversalPx

    private fun wantsOpen(velocity: Float) =
        way >= 0 && !reversed() && shouldOpen(pulled, -velocity, openAt, flick, against)

    /**
     * Whether a pull down inside an open drawer closes it: the opening rule with the pull and the
     * velocity read the other way up. No pull (a list's fling reporting itself) decides nothing.
     */
    private fun closes(velocity: Float) =
        pulling && shouldOpen(1f - pulled, velocity, openAt, flick, against)

    /** Follows the model: open animates the rest of the way in, closed the rest of the way out. */
    suspend fun settle(open: Boolean) {
        val from = handoff
        cancelPull()
        transitionJob?.cancel()
        if (from != null) {
            revealed.snapTo(from)
            if (handoff == from) handoff = null
        }
        animateToRest(open)
    }

    /**
     * Immediately places the drawer at its resting [open] state, cancelling any in-flight pull or
     * spring. Used when the activity stops so an interrupted close never freezes mid-screen.
     */
    fun snap(open: Boolean) {
        val target = if (open) 1f else 0f
        cancelPull()
        transitionJob?.cancel()
        givenJob?.cancel()
        lastGiven = 0f
        handoff = target
        transitionJob =
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                given.snapTo(0f)
                revealed.snapTo(target)
                handoff = null
            }
    }

    private fun cancelPull() {
        pulling = false
        way = 0
        heading = 0
        catchUpJob?.cancel()
    }

    private suspend fun animateToRest(open: Boolean) = coroutineScope {
        val target = if (open) 1f else 0f
        val springSpec = spring<Float>(stiffness = Spring.StiffnessMediumLow)
        lastGiven = 0f
        if (given.value != 0f || given.isRunning) {
            givenJob?.cancel()
            givenJob = launch { given.animateTo(0f, springSpec) }
        }
        if (revealed.value != target || revealed.isRunning) {
            revealed.animateTo(target, springSpec)
        }
    }
}

/**
 * [upwardsPxPerSecond] is the release velocity, positive when the finger moves up. A flick faster
 * than [flickPxPerSecond] opens on its own; any movement back faster than [againstPxPerSecond], a
 * change of mind, falls back on its own; otherwise past [openAt] (a share of the pull distance) a
 * released drawer opens, below it it falls back.
 */
internal fun shouldOpen(
    revealed: Float,
    upwardsPxPerSecond: Float,
    openAt: Float,
    flickPxPerSecond: Float,
    againstPxPerSecond: Float,
) =
    when {
        upwardsPxPerSecond > flickPxPerSecond -> true
        upwardsPxPerSecond < -againstPxPerSecond -> false
        else -> revealed >= openAt
    }
