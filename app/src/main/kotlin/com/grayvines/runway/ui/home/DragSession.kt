package com.grayvines.runway.ui.home

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.PointerId
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.ItemKind
import com.grayvines.runway.data.folderIdentity
import com.grayvines.runway.model.Footprint
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.widgets.WidgetProvider
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.drag.DragSource
import com.grayvines.runway.ui.drag.DragState
import com.grayvines.runway.ui.drag.DropPlan
import com.grayvines.runway.ui.drag.Point
import com.grayvines.runway.ui.drag.Settling
import com.grayvines.runway.ui.widgets.WidgetSpan

/**
 * The UI's view of dragging: what is lifted, where things would land, and how to report input.
 * Built once and kept: it reads the live drag through [State] objects, so a composable that reads
 * only what it needs (this cell lifted? this cell displaced?) recomposes only when that changes,
 * not on every move of the finger.
 */
@Stable
class DragSession(
    private val stateOf: State<DragState?>,
    private val settlingOf: State<Settling?>,
    /** Root-pixel centre of the cell a settling item belongs to, once that cell reports it. */
    private val settleTargetOf: State<Point?>,
    val onSettleTargetPositioned: (Point) -> Unit = {},
    val onSettled: (itemId: Long) -> Unit = {},
    private val onHold: (HomeItem, Container, Int, Bounds) -> Unit,
    private val onStart: (HomeItem, Container, Int, Point, Point) -> Unit,
    private val onStartNew: (DragSource, Point, Point) -> Unit,
    private val onMove: (Point) -> Unit,
    private val onEnd: () -> Unit,
    private val onCancel: () -> Unit,
    /** What a lifted widget looks like, for the overlay to carry; see [carry]. */
    private val pictureOf: MutableState<ImageBitmap?> = mutableStateOf(null),
) {
    val state: DragState?
        get() = stateOf.value

    val picture: ImageBitmap?
        get() = pictureOf.value

    val settling: Settling?
        get() = settlingOf.value

    val settleTarget: Point?
        get() = settleTargetOf.value

    val draggedId: Long?
        get() = state?.source?.itemId

    /**
     * The pointer carrying the drag, from the moment a cell reports its start until the release:
     * the root tracker follows this one, whichever finger came down first. Read by the tracker on
     * the very event that starts the drag, before [state] (a frame behind) shows it.
     */
    var finger: PointerId? = null
        private set

    /** The item the dragged app would fold into if let go now. */
    val foldTargetId: Long?
        get() = (state?.plan as? DropPlan.Fold)?.into

    /** Where a displaced item is previewed, once the finger has rested on its target. */
    fun previewFor(id: Long): Footprint? =
        state?.takeIf { it.rested }?.let { (it.plan as? DropPlan.Move)?.displaced?.get(id) }

    /** Pointer tracking after a start comes from the root ([tracksDrag]), not the cell. */
    fun move(pointer: Point) = onMove(pointer)

    fun end() {
        finger = null
        onEnd()
    }

    fun cancel() {
        finger = null
        onCancel()
    }

    /** A drawer app: a hold shows its menu; moving after the hold pulls a new placement out. */
    fun handlersForDrawer(app: AppEntry) =
        handlers(
            onHold = { cell -> onHold(app.asItem(), Container.DRAWER, 0, cell) },
            onStart = { pointer, grab -> onStartNew(app.fresh(), pointer, grab) },
        )

    /**
     * A drawer folder: a hold shows its menu; moving after the hold carries the folder out, and the
     * drop gives it a second placement in a cell.
     */
    fun handlersForDrawerFolder(folder: HomeItem) =
        handlers(
            onHold = { cell -> onHold(folder, Container.DRAWER, 0, cell) },
            onStart = { pointer, grab ->
                folder.folderId?.let { onStartNew(fresh(newFolder = it), pointer, grab) }
            },
        )

    /**
     * An app in an open folder, which it leaves when the drop lands if [leaving] names the folder;
     * out of a drawer folder it is copied, and stays.
     */
    fun handlersForFolder(app: AppEntry, leaving: Long?) =
        handlers(
            onHold = {},
            onStart = { pointer, grab ->
                onStartNew(app.fresh(fromFolder = leaving), pointer, grab)
            },
        )

    fun handlersFor(item: HomeItem, page: Int, container: Container = Container.HOME) =
        handlers(
            onHold = { cell -> onHold(item, container, page, cell) },
            onStart = { pointer, grab -> onStart(item, container, page, pointer, grab) },
        )

    /** A widget is carried as a picture of itself, taken by its cell as it lifts. */
    fun carry(picture: ImageBitmap?) {
        pictureOf.value = picture
    }

    /**
     * A widget in the picker: moving after the hold carries its preview out, [span] cells big and
     * held at [grab] (px within it), and the drop binds and places one.
     */
    fun handlersForNewWidget(provider: WidgetProvider, span: WidgetSpan, grab: Point) =
        handlers(
            onHold = {},
            onStart = { pointer, _ ->
                carry(provider.preview)
                onStartNew(freshWidget(provider.key, span), pointer, grab)
            },
        )

    /**
     * Handlers that also name the finger. Only while no drag is live: a second finger's long press
     * during one changes nothing, and must not take the drag away from the finger carrying it.
     */
    private fun handlers(
        onHold: (cell: Bounds) -> Unit,
        onStart: (pointer: Point, grab: Point) -> Unit,
    ) = DragHandlers(onHold, onStart, onFinger = { if (state == null) finger = it })
}

/** A drawer app as the menu sees it: no placement of its own. */
internal fun AppEntry.asItem() = HomeItem(0, ItemKind.APP, 0, 0, 1, 1, label, this)

/**
 * A drag of this app with no cell yet, out of the drawer or out of the folder it is [fromFolder].
 */
internal fun AppEntry.fresh(fromFolder: Long? = null) = fresh(newApp = ref, fromFolder = fromFolder)

/** A drag of a widget with no cell yet, out of the picker. */
internal fun freshWidget(key: String, span: WidgetSpan) =
    DragSource(
        0,
        ItemKind.WIDGET,
        Container.DRAWER,
        0,
        0,
        0,
        spanX = span.width,
        spanY = span.height,
        newWidget = key,
    )

/** A drag of something with no cell yet; its kind follows what is set. */
internal fun fresh(newApp: AppRef? = null, fromFolder: Long? = null, newFolder: Long? = null) =
    DragSource(
        0,
        if (newFolder != null) ItemKind.FOLDER else ItemKind.APP,
        Container.DRAWER,
        0,
        0,
        0,
        newApp = newApp,
        fromFolder = fromFolder,
        newFolder = newFolder,
        identity = newApp?.identity ?: newFolder?.let(::folderIdentity),
    )
