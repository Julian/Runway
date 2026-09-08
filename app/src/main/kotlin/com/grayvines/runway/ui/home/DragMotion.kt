package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * The shapes and tempo of drag motion, in one place so the pieces move together: a lift is one push
 * (the icon comes forward as the home area steps back, on the same spring), and a release is one
 * landing (the icon settles as the home area returns).
 */
internal object DragMotion {
    /** A touched icon shrinks a little under the finger. */
    const val PRESSED_SCALE = 0.9f

    /** The lifted icon grows: a finger covers it, and the growth says "picked up". */
    const val LIFTED_SCALE = 1.2f

    /**
     * Over an icon it would fold into, the lifted icon shrinks to this, so the tile forming under
     * it and the icon it would join both stay in view.
     */
    const val FOLDING_SCALE = 0.6f
    const val FOLDING_MS = 150

    /** While dragging the home area pulls back, as if seen from a step further away. */
    const val ZOOM = 0.94f
    const val BORDER_ALPHA = 0.35f

    /** Lifting overshoots slightly, like something snatched up. */
    val lift = spring<Float>(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)

    /** Landing does not bounce. */
    val settle = spring<Float>(stiffness = Spring.StiffnessMediumLow)
}
