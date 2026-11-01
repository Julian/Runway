package com.grayvines.runway.ui.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.util.SizeF
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.drawToBitmap
import com.grayvines.runway.appGraph
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.home.DragHandlers
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.home.dragAfterLongPress
import kotlinx.coroutines.withTimeoutOrNull

const val WIDGET_TAG = "widget"

private const val PLACEHOLDER_ALPHA = 0.25f
private val PLACEHOLDER_CORNER = 12.dp
private val PLACEHOLDER_PADDING = 12.dp

/**
 * A widget over its cells, each [cell] big: the system's host view, told its size in dp so the
 * provider lays out for it. The size it is told is the stored one: while a resize handle pulls it
 * the view stretches to the cells it snaps to, and the provider hears once, when the resize is
 * saved, rather than at every snap. A widget nothing is bound to (its app is gone, or a restore has
 * not bound it) is drawn as a placeholder naming the app, with the same hold and drag as a live
 * widget so it can be moved or removed. A long press is the launcher's ([drag]), as on an icon;
 * every other touch is the widget's. Lifted, the cell goes invisible while [session] carries a
 * picture of it; settling after a drop, it reports where it is so the picture can come to it. While
 * the resize frame is around it ([framedBy]) it reports where it is to that.
 */
@Composable
@Suppress("LongParameterList") // one cell's inputs, passed once
fun WidgetCell(
    item: HomeItem,
    cell: DpSize,
    modifier: Modifier = Modifier,
    drag: DragHandlers? = null,
    session: DragSession? = null,
    lifted: Boolean = false,
    settlingHere: DragSession? = null,
    framedBy: WidgetResizeSession? = null,
) {
    val context = LocalContext.current
    val host = context.appGraph.widgets
    val id = item.appWidgetId
    val info = remember(id, item.label) { id?.let(host::info) }
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var frame by remember { mutableStateOf<WidgetFrame?>(null) }
    // The frame may arrive after the cell's last layout: it is told where the cell is at once.
    LaunchedEffect(framedBy) { coords?.let { framedBy?.positioned(item.id, it.boundsInRoot()) } }
    val handlers by rememberWidgetHandlers(drag, session) { frame }
    // Framed, the widget is not live: its view sees no touch, and a tap puts the frame away. The
    // cell's own gestures go on as before, so a hold lifts the framed widget again.
    val framed = framedBy?.frames(item.id) == true
    val dismissFrame = rememberUpdatedState { framedBy?.onDismiss?.invoke() }
    val cellModifier =
        modifier
            .fillMaxSize()
            .testTag(WIDGET_TAG)
            .pointerInput(framed) { if (framed) detectTaps { dismissFrame.value() } }
            // Invisible while carried: removing the view would end its own gesture.
            .graphicsLayer { alpha = if (lifted) 0f else 1f }
            .onGloballyPositioned {
                coords = it
                framedBy?.positioned(item.id, it.boundsInRoot())
                if (settlingHere != null) {
                    val c = it.boundsInRoot().center
                    settlingHere.onSettleTargetPositioned(Point(c.x, c.y))
                }
            }
            .dragAfterLongPress(item.id, { coords }, { handlers })
    if (id == null || info == null) {
        frame = null
        Placeholder(item.label, cellModifier)
        return
    }
    val label = remember(info) { info.loadLabel(context.packageManager) }
    val size = SizeF(cell.width.value * item.spanX, cell.height.value * item.spanY)
    AndroidView(
        factory = { ctx ->
            WidgetFrame(ctx).hosting(host.createView(ctx, id, info)).also { frame = it }
        },
        update = { f ->
            f.muted = framed
            f.widget?.updateAppWidgetSize(Bundle(), listOf(size))
        },
        modifier = cellModifier.semantics { contentDescription = label },
    )
}

/**
 * Stand-in for a widget nothing is bound to: a dim rounded card over its cells with the app's name.
 */
@Composable
private fun Placeholder(label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(
                Color.White.copy(alpha = PLACEHOLDER_ALPHA),
                RoundedCornerShape(PLACEHOLDER_CORNER),
            )
            .padding(PLACEHOLDER_PADDING),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The hold is the launcher's from then on: the widget must not take the release as a tap. A move
 * after it lifts the widget, which travels as a picture taken at that moment. Read through state by
 * the gesture, which outlives compositions: the session is replaced after every drop, and a picture
 * handed to a stale one would never be drawn.
 */
@Composable
private fun rememberWidgetHandlers(
    drag: DragHandlers?,
    session: DragSession?,
    frame: () -> WidgetFrame?,
) =
    rememberUpdatedState(
        remember(drag, session) {
            drag?.let {
                DragHandlers(
                    onHold = { bounds ->
                        frame()?.claim()
                        it.onHold(bounds)
                    },
                    onStart = { pointer, grab ->
                        session?.carry(frame()?.picture())
                        it.onStart(pointer, grab)
                    },
                    onFinger = it.onFinger,
                )
            }
        }
    )

/**
 * A finger down and up again within the hold time, without moving past slop: a tap. Nothing is
 * consumed, so the cell's hold-then-drag sees the same events.
 */
private suspend fun PointerInputScope.detectTaps(onTap: () -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val up =
            withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                waitForUpOrCancellation()
            }
        if (
            up != null && (up.position - down.position).getDistance() <= viewConfiguration.touchSlop
        ) {
            onTap()
        }
    }
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

    /** While the resize frame is up: every touch is kept from the widget. */
    var muted = false

    fun hosting(view: AppWidgetHostView): WidgetFrame {
        widget = view
        addView(
            view,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        return this
    }

    /**
     * The finger is the launcher's now. The widget is told at once, not on the next move: a list
     * row it was pressing would otherwise still be lit in the picture taken as the drag starts.
     */
    fun claim() {
        claimed = true
        val now = SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        widget?.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /**
     * What the widget looks like at rest, or null before it has been laid out. A ripple still
     * fading is jumped to its end first, or the picture would carry a stale press.
     */
    @Suppress("TooGenericExceptionCaught") // whatever a provider's view throws while drawn
    fun picture(): ImageBitmap? {
        val view = widget
        val ready = view != null && view.isLaidOut && view.width > 0 && view.height > 0
        if (view == null || !ready) {
            Log.w(TAG, "no picture of the widget: view=$view laidOut=${view?.isLaidOut}")
            return null
        }
        return try {
            view.jumpDrawablesToCurrentState()
            view.drawToBitmap().asImageBitmap()
        } catch (e: RuntimeException) {
            Log.w(TAG, "could not picture the widget", e)
            null
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) claimed = false
        return super.dispatchTouchEvent(ev)
    }

    /** Intercepting sends the widget a cancel; the frame itself then declines what follows. */
    override fun onInterceptTouchEvent(ev: MotionEvent) = claimed || muted

    private companion object {
        const val TAG = "Runway"
    }
}
