package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch

const val REMOVE_BIN_TAG = "remove-bin"
const val REMOVE_BIN_LIT_TAG = "remove-bin-lit"

/** Small enough to sit between two rows of icons without hiding either. */
private val BIN = 36.dp
private val BIN_ICON = 20.dp
private val RESTING = Color(0xFF202124)
private val LIT = Color(0xFFD93025)

/** Lit, the bin grows past the shrunken icon over it, so the red shows all round. */
private const val LIT_SCALE = 1.25f

/**
 * What counts as on the bin: wider than the circle, so a finger finds it without taking aim, and
 * shorter, since whatever it takes is the edge of the cell above it and the dock slot below. Half
 * its height stays inside the fifth of a cell outside where a drop folds.
 */
private val REACH = DpSize(72.dp, 28.dp)

/**
 * A circle with a bin in it, between the dock and the rows above it: it comes forward with a lifted
 * placement (an icon, folder or widget off a page or the dock, not anything out of the drawer or a
 * folder) and goes back as that lands. While the finger is on it, it lights up, and letting go
 * there removes the placement. Its middle is [at], from the top of the screen; what counts as on it
 * ([REACH], around that) is reported whether or not the bin shows ([DragSession.onBinPositioned]).
 */
@Composable
fun RemoveBin(drag: DragSession, at: Dp) {
    val removable by remember(drag) { derivedStateOf { drag.removable } }
    val lit by remember(drag) { derivedStateOf { drag.removing } }
    val motion = rememberBinMotion(removable, lit)
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset(y = at - REACH.height / 2)
                .size(REACH)
                .onGloballyPositioned { drag.onBinPositioned(it.boundsInRoot().toBounds()) }
        )
        if (motion.visible) {
            Box(
                Modifier.align(Alignment.TopCenter)
                    .offset(y = at - BIN / 2)
                    .size(BIN)
                    .graphicsLayer {
                        // A landing let go of mid-bounce can dip just past nothing.
                        val shown = motion.shown.value.coerceAtLeast(0f)
                        val scale = shown * lerp(1f, LIT_SCALE, motion.glow.value)
                        scaleX = scale
                        scaleY = scale
                        alpha = shown.coerceAtMost(1f)
                    }
                    .drawBehind { drawCircle(lerp(RESTING, LIT, motion.glow.value)) }
                    .testTag(REMOVE_BIN_TAG),
                contentAlignment = Alignment.Center,
            ) {
                if (lit) Box(Modifier.matchParentSize().testTag(REMOVE_BIN_LIT_TAG))
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(BIN_ICON),
                )
            }
        }
    }
}

/** How far the bin has come forward and how far it has lit up, each from 0 to 1. */
@Stable
private class BinMotion {
    val shown = Animatable(0f)
    val glow = Animatable(0f)

    /** Anything to draw at all; read in composition, so only a change of answer recomposes. */
    val visible by derivedStateOf { shown.value > 0f }
}

/**
 * The bin comes forward on the lift's spring and goes back on the landing's, so it moves as one
 * with the icon and the home area. Let go, it keeps whatever glow it had while it goes: a bin that
 * turned grey as it took the icon would say it had not.
 */
@Composable
private fun rememberBinMotion(removable: Boolean, lit: Boolean): BinMotion {
    val motion = remember { BinMotion() }
    LaunchedEffect(removable, lit) {
        if (removable) {
            // A lift after the last drop's bin has gone starts unlit, whatever that one was.
            if (motion.shown.value == 0f) motion.glow.snapTo(0f)
            launch { motion.glow.animateTo(if (lit) 1f else 0f, tween(DragMotion.FOLDING_MS)) }
            motion.shown.animateTo(1f, DragMotion.lift)
        } else {
            motion.shown.animateTo(0f, DragMotion.settle)
        }
    }
    return motion
}
