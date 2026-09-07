package com.grayvines.runway.ui.shade

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

const val SHADE_HINT_TAG = "shade-hint"

private const val DIM = 0.5f
private val RING = 56.dp
private val BELL = 28.dp
private val RING_TOP = 24.dp
private val RING_COLOUR = Color(0xFF202124)

/**
 * Shown while a swipe down is on its way to the notification shade: the home screen dims by [given]
 * (0 to 1, how close the pull is to opening the shade) and a bell in a circle grows in at the top.
 * Nothing at all at 0, so it costs nothing at rest.
 */
@Composable
fun ShadeHint(given: () -> Float, insets: PaddingValues) {
    if (given() <= 0f) return
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = given() * DIM }.background(Color.Black))
    Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier.padding(top = RING_TOP)
                .size(RING)
                .graphicsLayer {
                    val g = given()
                    alpha = g
                    scaleX = g
                    scaleY = g
                }
                .background(RING_COLOUR, CircleShape)
                .testTag(SHADE_HINT_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Notifications,
                contentDescription = "Notifications",
                tint = Color.White,
                modifier = Modifier.size(BELL),
            )
        }
    }
}
