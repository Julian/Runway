package com.grayvines.runway.ui.widgets

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.widgets.WidgetProvider

const val WIDGET_PICKER_TAG = "widget-picker"
const val WIDGET_LIST_TAG = "widget-list"
const val WIDGET_CHOICE_TAG = "widget-choice"

/** The picker as the screen sees it: whether it is up, what it lists, and how to drive it. */
class WidgetPickerSession(
    val open: Boolean,
    val providers: List<WidgetProvider>?,
    /** A widget chosen, with the size of a grid cell in dp, which decides its span. */
    val onPick: (WidgetProvider, cellWidthDp: Float, cellHeightDp: Float) -> Unit,
    val onDismiss: () -> Unit,
)

private val SURFACE = Color(0xFF202124)
private val CORNER = 24.dp
private val APP_ICON = 28.dp
private val PREVIEW_HEIGHT = 72.dp
private val PREVIEW_WIDTH = 120.dp
private const val SCRIM_ALPHA = 0.4f
private const val SHEET_HEIGHT = 0.7f

/**
 * The widgets the device offers, by app, rising from the bottom of the screen. Tapping one adds it
 * to the page; a tap outside, or back, closes the picker. [providers] is null while they load.
 */
@Composable
fun WidgetPicker(
    providers: List<WidgetProvider>?,
    onPick: (WidgetProvider) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize()) {
        // A sibling, not a parent: a clickable parent would merge the sheet's semantics into it.
        Spacer(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
        )
        Surface(
            modifier =
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(SHEET_HEIGHT)
                    .testTag(WIDGET_PICKER_TAG),
            shape = RoundedCornerShape(topStart = CORNER, topEnd = CORNER),
            color = SURFACE,
            contentColor = Color.White,
            shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(top = 16.dp)) {
                Text(
                    "Widgets",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                if (providers == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    Choices(providers, onPick)
                }
            }
        }
    }
}

/** The widgets under their apps' names, in the order [providers] came in. */
@Composable
private fun Choices(providers: List<WidgetProvider>, onPick: (WidgetProvider) -> Unit) {
    val groups = providers.groupBy { it.appLabel }
    // The last rows scroll up from under the navigation bar rather than staying beneath it.
    LazyColumn(
        Modifier.fillMaxSize().testTag(WIDGET_LIST_TAG),
        contentPadding = WindowInsets.navigationBars.asPaddingValues(),
    ) {
        groups.forEach { (appLabel, widgets) ->
            item(key = "app:$appLabel") { AppHeader(appLabel, widgets.first()) }
            items(widgets, key = { it.key }) { widget -> Choice(widget) { onPick(widget) } }
        }
    }
}

@Composable
private fun AppHeader(appLabel: String, first: WidgetProvider) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        first.appIcon?.let { Image(it, contentDescription = null, Modifier.size(APP_ICON)) }
        Spacer(Modifier.width(12.dp))
        Text(appLabel, style = MaterialTheme.typography.titleSmall)
    }
}

/** One widget: its picture (or its app's icon), its name, and the cells it is designed for. */
@Composable
private fun Choice(widget: WidgetProvider, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier.fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag(WIDGET_CHOICE_TAG),
    ) {
        Box(Modifier.width(PREVIEW_WIDTH).height(PREVIEW_HEIGHT), Alignment.Center) {
            val picture = widget.preview ?: widget.appIcon
            picture?.let {
                Image(
                    it,
                    contentDescription = null,
                    Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column {
            Text(widget.label, style = MaterialTheme.typography.bodyLarge)
            val info = widget.info
            if (info.targetCellWidth > 0 && info.targetCellHeight > 0) {
                Text(
                    "${info.targetCellWidth} × ${info.targetCellHeight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.7f),
                )
            }
        }
    }
}
