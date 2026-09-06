package com.grayvines.runway.ui.drag

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What resting a dragged item at a page edge does over time: a flip every [FLIP_MS]; past the last
 * page, one new page after [ADD_PAGE_MS] of holding there. Time comes from the coroutine clock, so
 * tests run it under virtual time.
 */
class EdgeDwell(private val scope: CoroutineScope, private val actions: Actions) {
    interface Actions {
        /** True when flipping in [edge]'s direction has nowhere to go. */
        fun isPastTheEnd(edge: Edge): Boolean

        fun flip(delta: Int)

        /** Adds a page beyond the last and returns once it can be flipped to; false on failure. */
        suspend fun addPage(): Boolean
    }

    private var job: Job? = null
    private var edge: Edge? = null

    /** The edge now under the finger, or null; a change restarts the timing. */
    fun hover(edge: Edge?) {
        if (edge == this.edge) return
        this.edge = edge
        job?.cancel()
        job = edge?.let { scope.launch { dwellAt(it) } }
    }

    fun stop() = hover(null)

    private suspend fun dwellAt(edge: Edge) {
        var heldAtEnd = 0L
        var addedPage = false
        while (true) {
            delay(FLIP_MS)
            if (!actions.isPastTheEnd(edge)) {
                heldAtEnd = 0 // arriving at the end starts the long hold from zero
                actions.flip(edge.pageDelta)
            } else {
                heldAtEnd += FLIP_MS
                if (!addedPage && heldAtEnd >= ADD_PAGE_MS) {
                    addedPage = true // one attempt per hold, whether or not it worked
                    if (actions.addPage()) actions.flip(edge.pageDelta)
                }
            }
        }
    }

    companion object {
        const val FLIP_MS = 450L
        const val ADD_PAGE_MS = 1_350L
    }
}
