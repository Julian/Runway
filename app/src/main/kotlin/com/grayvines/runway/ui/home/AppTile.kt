package com.grayvines.runway.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds

/** How long a finger must rest on an icon before it lifts; longer than the platform default. */
internal const val LIFT_HOLD_MS = 550L

/**
 * An icon over its label, the way everything in a grid is drawn: on the home screen, in the drawer
 * and in an open folder. [content] fills the top of the column; the label, when [labelled], sits
 * under it in one line. The icon in [content] already describes the tile by [label], so the visible
 * text is silent to TalkBack rather than read a second time.
 */
@Composable
fun AppTile(
    label: String,
    labelled: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        content()
        if (labelled) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier =
                    Modifier.fillMaxWidth().padding(horizontal = 2.dp).clearAndSetSemantics {},
            )
        }
    }
}

/**
 * An app's icon at [iconSize] over its label, across the width it is given. Tapping it calls
 * [onClick]; while the finger is down the icon shrinks a little, as a home icon does.
 */
@Composable
fun AppTile(
    app: AppEntry,
    iconSize: Dp,
    labelled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) DragMotion.PRESSED_SCALE else 1f, label = "press")
    AppTile(
        app.label,
        labelled,
        Modifier.clickable(interactionSource = interactions, indication = null, onClick = onClick)
            .then(modifier)
            .fillMaxWidth(),
    ) {
        AppIcon(
            app,
            Modifier.size(iconSize).graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        )
    }
}

/**
 * The platform's touch settings with a longer long press: a lift is deliberate. Longer, never
 * shorter: someone who asked the accessibility settings for a long touch and hold gets at least
 * that here too.
 */
@Composable
internal fun rememberLiftConfiguration(): ViewConfiguration {
    val viewConfiguration = LocalViewConfiguration.current
    return remember(viewConfiguration) {
        object : ViewConfiguration by viewConfiguration {
            override val longPressTimeoutMillis: Long
                get() = maxOf(LIFT_HOLD_MS, viewConfiguration.longPressTimeoutMillis)
        }
    }
}

/** Tracks a node's bounds in root pixels so clicks and long presses can report where it sits. */
internal class RootBounds {
    var coords by mutableStateOf<LayoutCoordinates?>(null)
    val modifier = Modifier.onGloballyPositioned { coords = it }

    fun current(): Bounds = coords?.boundsInRoot()?.toBounds() ?: Bounds(0f, 0f, 0f, 0f)
}

@Composable internal fun rememberRootBounds(): RootBounds = remember { RootBounds() }

/**
 * A [clickable] that passes the node's root bounds (px) to [onClick], and when [dragKey] and [drag]
 * are given, also lets a long press on the same node lift it without tracking its coordinates a
 * second time. When [interactions] is given, the click uses it without a ripple, as an icon tile
 * does.
 */
@Composable
fun Modifier.clickableWithBounds(
    interactions: MutableInteractionSource? = null,
    dragKey: Any? = null,
    drag: DragHandlers? = null,
    onClick: (Bounds) -> Unit,
): Modifier {
    val bounds = rememberRootBounds()
    val handlers by rememberUpdatedState(drag)
    val click = { onClick(bounds.current()) }
    val clickable =
        if (interactions != null) {
            Modifier.clickable(interactionSource = interactions, indication = null, onClick = click)
        } else {
            Modifier.clickable(onClick = click)
        }
    val liftable =
        if (dragKey != null) {
            Modifier.dragAfterLongPress(dragKey, { bounds.coords }, { handlers })
        } else {
            Modifier
        }
    return then(bounds.modifier).then(clickable).then(liftable)
}

/**
 * Lets a finger lift what this is on: a long press holds, and moving after that drags, reported to
 * [drag] in root pixels. Nothing happens without handlers.
 */
@Composable
fun Modifier.liftable(key: Any, drag: DragHandlers?): Modifier {
    // The gesture coroutine outlives recompositions: both of these must always be current.
    val bounds = rememberRootBounds()
    val handlers by rememberUpdatedState(drag)
    return then(bounds.modifier).dragAfterLongPress(key, { bounds.coords }, { handlers })
}
