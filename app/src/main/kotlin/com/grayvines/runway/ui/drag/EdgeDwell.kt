package com.grayvines.runway.ui.drag

import com.grayvines.runway.data.Container
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A page scroll requested during a drag: relative ([By], from dwelling at an edge) or exact ([To],
 * when returning to a source page after a refused or cancelled drop).
 */
sealed interface PageFlip {
    data class By(val delta: Int) : PageFlip

    data class To(val page: Int) : PageFlip
}

/**
 * What resting a dragged item at a page edge does over time: a flip every [FLIP_MS]; past the last
 * page, one new page after [ADD_PAGE_MS] of holding there. Home and dock pages alike. Time comes
 * from the coroutine clock, so tests run it under virtual time.
 */
class EdgeDwell(private val scope: CoroutineScope, private val actions: Actions) {
    interface Actions {
        /** True when [hover] is at the start of the pager, with no earlier page to flip to. */
        fun isAtStart(hover: EdgeHover): Boolean = false

        /** True when flipping in [hover]'s direction has nowhere to go. */
        fun isPastTheEnd(hover: EdgeHover): Boolean

        /** Page index change when flipping at [hover]. */
        fun delta(hover: EdgeHover): Int = hover.edge.pageDelta

        fun flip(container: Container, delta: Int)

        /**
         * Adds a page beyond [container]'s last and returns once it can be flipped to; false on
         * failure.
         */
        suspend fun addPage(container: Container): Boolean
    }

    private var job: Job? = null
    private var hover: EdgeHover? = null

    /** The edge now under the finger, or null; a change restarts the timing. */
    fun hover(hover: EdgeHover?) {
        if (hover == this.hover) return
        this.hover = hover
        job?.cancel()
        job = hover?.let { scope.launch { dwellAt(it) } }
    }

    fun stop() = hover(null)

    private suspend fun dwellAt(hover: EdgeHover) {
        var heldAtEnd = 0L
        var addedPage = false
        while (true) {
            delay(FLIP_MS)
            when {
                actions.isAtStart(hover) -> {
                    heldAtEnd = 0
                }
                !actions.isPastTheEnd(hover) -> {
                    heldAtEnd = 0 // arriving at the end starts the long hold from zero
                    actions.flip(hover.container, actions.delta(hover))
                }
                else -> {
                    heldAtEnd += FLIP_MS
                    if (!addedPage && heldAtEnd >= ADD_PAGE_MS) {
                        addedPage = true // one attempt per hold, whether or not it worked
                        if (actions.addPage(hover.container)) {
                            actions.flip(hover.container, actions.delta(hover))
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val FLIP_MS = 450L
        const val ADD_PAGE_MS = 1_350L
    }
}
