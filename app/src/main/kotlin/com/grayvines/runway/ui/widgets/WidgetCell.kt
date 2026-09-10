package com.grayvines.runway.ui.widgets

import android.os.Bundle
import android.util.SizeF
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import com.grayvines.runway.appGraph
import com.grayvines.runway.ui.home.HomeItem

const val WIDGET_TAG = "widget"

/**
 * A widget over its cells, each [cell] big: the system's host view, told its size in dp so the
 * provider lays out for it. A widget nothing is bound to shows a stand-in until placeholders come.
 */
@Composable
fun WidgetCell(item: HomeItem, cell: DpSize, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val host = context.appGraph.widgets
    val id = item.appWidgetId
    val info = remember(id) { id?.let(host::info) }
    if (id == null || info == null) {
        Text(item.label, color = Color.White, style = MaterialTheme.typography.labelSmall)
        return
    }
    val label = remember(info) { info.loadLabel(context.packageManager) }
    val size = SizeF(cell.width.value * item.spanX, cell.height.value * item.spanY)
    AndroidView(
        factory = { host.createView(it, id, info) },
        update = { view -> view.updateAppWidgetSize(Bundle(), listOf(size)) },
        modifier =
            modifier.fillMaxSize().testTag(WIDGET_TAG).semantics { contentDescription = label },
    )
}
