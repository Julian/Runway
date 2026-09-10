package com.grayvines.runway.ui.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.os.Bundle
import android.util.SizeF
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.viewinterop.AndroidView
import com.grayvines.runway.appGraph
import com.grayvines.runway.ui.home.DragHandlers
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.home.dragAfterLongPress

const val WIDGET_TAG = "widget"

/**
 * A widget over its cells, each [cell] big: the system's host view, told its size in dp so the
 * provider lays out for it. A widget nothing is bound to shows a stand-in until placeholders come.
 * A long press is the launcher's ([drag]), as on an icon; every other touch is the widget's.
 */
@Composable
fun WidgetCell(
    item: HomeItem,
    cell: DpSize,
    modifier: Modifier = Modifier,
    drag: DragHandlers? = null,
) {
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
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var frame by remember { mutableStateOf<WidgetFrame?>(null) }
    // The hold is the launcher's from then on: the widget must not take the release as a tap.
    val handlers =
        remember(drag) {
            drag?.let {
                DragHandlers(
                    onHold = { bounds ->
                        frame?.claim()
                        it.onHold(bounds)
                    },
                    onStart = it.onStart,
                )
            }
        }
    AndroidView(
        factory = { ctx ->
            WidgetFrame(ctx).hosting(host.createView(ctx, id, info)).also { frame = it }
        },
        update = { f -> f.widget?.updateAppWidgetSize(Bundle(), listOf(size)) },
        modifier =
            modifier
                .fillMaxSize()
                .testTag(WIDGET_TAG)
                .semantics { contentDescription = label }
                .onGloballyPositioned { coords = it }
                .dragAfterLongPress(item.id, { coords }, { handlers }),
    )
}

/**
 * Holds the widget's view and stands between it and the finger: touches are the widget's until a
 * long press [claim]s the finger, after which the widget is sent a cancel (so the release is not
 * its tap) and sees nothing more of that finger. Compose's own detectors see every event
 * regardless, so the launcher's long press needs no help from here.
 */
private class WidgetFrame(context: Context) : FrameLayout(context) {
    var widget: AppWidgetHostView? = null
        private set

    private var claimed = false

    fun hosting(view: AppWidgetHostView): WidgetFrame {
        widget = view
        addView(
            view,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return this
    }

    fun claim() {
        claimed = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) claimed = false
        return super.dispatchTouchEvent(ev)
    }

    /** Intercepting sends the widget a cancel; the frame itself then declines what follows. */
    override fun onInterceptTouchEvent(ev: MotionEvent) = claimed
}
