package com.grayvines.runway.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import com.grayvines.runway.data.settings.DrawerSwipe
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drawer.DrawerMotion
import com.grayvines.runway.ui.drawer.drawerPull
import com.grayvines.runway.ui.drawer.releasesAbandonedPull

/** What a vertical swipe on the home screen can ask for. */
class DrawerActions(val open: () -> Unit, val close: () -> Unit, val openShade: () -> Unit)

/** The drawer's motion, its release decision, and what a pull must know, wired once. */
internal class DrawerControls(
    val motion: DrawerMotion,
    val release: (velocity: Float) -> Unit,
    val startsOnWidget: (Point) -> Boolean,
)

/**
 * The pull gesture, for one surface. Each surface that pulls the drawer (the pages, the search bar)
 * builds its own: the modifier keeps that surface's coordinates, so one shared between two nodes
 * converts one surface's fingers through the other's position.
 */
@Composable
internal fun Modifier.drawerPull(drawer: DrawerControls): Modifier =
    drawerPull(
        drawer.motion,
        drawer.release,
        startsOnWidget = { drawer.startsOnWidget(Point(it.x, it.y)) },
    )

@Composable
internal fun rememberDrawer(
    open: Boolean,
    actions: DrawerActions,
    /**
     * Whether a finger landing at this root point is on a widget, whose vertical drags are its own.
     */
    startsOnWidget: (Point) -> Boolean = { false },
): DrawerControls {
    val scope = rememberCoroutineScope()
    val motion = remember { DrawerMotion(scope) }
    LaunchedEffect(open) { motion.settle(open) }
    val release = { velocity: Float ->
        motion.release(velocity, open, actions.open, actions.close, actions.openShade)
    }
    return DrawerControls(motion, release, startsOnWidget)
}

/** Told the screen height once it is known, so the motion knows how far a pull travels. */
@Composable
internal fun PlaceDrawer(drawer: DrawerControls, height: Dp, swipe: DrawerSwipe) {
    val density = LocalDensity.current
    drawer.motion.laidOut(with(density) { height.toPx() }, density.density, swipe)
}

@Composable
internal fun Modifier.releasesAbandonedPull(drawer: DrawerControls) =
    releasesAbandonedPull(drawer.motion, drawer.release)
