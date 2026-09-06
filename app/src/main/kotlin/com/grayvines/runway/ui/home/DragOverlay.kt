package com.grayvines.runway.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import com.grayvines.runway.ui.drag.DragState

const val DRAG_OVERLAY_TAG = "drag-overlay"

/** The lifted icon grows a little: a finger covers it, and the growth says "picked up". */
private const val LIFTED_SCALE = 1.2f

/** Draws the lifted cell under the pointer, in root coordinates, above everything else. */
@Composable
fun DragOverlay(drag: DragState?, item: HomeItem?, cell: DpSize, iconSize: Dp) {
    val app = item?.app
    if (drag == null || app == null) return
    // Starts at rest size and swells up, overshooting slightly, like something being picked up.
    val scale = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        scale.animateTo(
            LIFTED_SCALE,
            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        )
    }
    Box(
        modifier =
            Modifier.offset {
                    IntOffset(
                        (drag.pointer.x - drag.grab.x).toInt(),
                        (drag.pointer.y - drag.grab.y).toInt(),
                    )
                }
                .size(cell),
        contentAlignment = Alignment.Center,
    ) {
        AppIcon(
            app,
            Modifier.size(iconSize)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                }
                .testTag(DRAG_OVERLAY_TAG),
        )
    }
}
