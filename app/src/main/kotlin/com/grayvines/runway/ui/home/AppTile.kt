package com.grayvines.runway.ui.home

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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.apps.AppEntry

/**
 * An icon over its label, the way everything in a grid is drawn: on the home screen, in the drawer
 * and in an open folder. [content] fills the top of the column; the label, when [labelled], sits
 * under it in one line.
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
                modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp),
            )
        }
    }
}

/** An app's icon at [iconSize] over its label, across the width it is given. */
@Composable
fun AppTile(app: AppEntry, iconSize: Dp, labelled: Boolean, modifier: Modifier = Modifier) {
    AppTile(app.label, labelled, modifier.fillMaxWidth()) { AppIcon(app, Modifier.size(iconSize)) }
}

/**
 * Lets a finger lift what this is on: a long press holds, and moving after that drags, reported to
 * [drag] in root pixels. Nothing happens without handlers.
 */
@Composable
fun Modifier.liftable(key: Any, drag: DragHandlers?): Modifier {
    // The gesture coroutine outlives recompositions: both of these must always be current.
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val handlers by rememberUpdatedState(drag)
    return onGloballyPositioned { coords = it }.dragAfterLongPress(key, { coords }, { handlers })
}
