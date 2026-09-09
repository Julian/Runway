package com.grayvines.runway.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.home.AppTile
import com.grayvines.runway.ui.home.DragHandlers
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.FolderIcon
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.home.liftable
import com.grayvines.runway.ui.home.toBounds
import kotlinx.coroutines.launch

const val DRAWER_TAG = "drawer"
const val DRAWER_ITEM_TAG = "drawer-app"
const val DRAWER_FOLDER_TAG = "drawer-folder"
const val DRAWER_SEARCH_TAG = "drawer-search"
const val DRAWER_LIST_TAG = "drawer-list"

/** Opaque once on: icons showing through would be noise over icons. */
private val SURFACE = Color(0xFF0E0E10)

/** Never fainter than this, and fully opaque this far in: seen from the first pixel. */
private const val FAINTEST = 0.5f
private const val OPAQUE_AT = 0.25f

/** Room between the edges of the screen and what is on the drawer. */
private val MARGIN = 16.dp

/** With more columns than home the icons shrink to this share of the drawer's cell. */
private const val ICON_SHARE = 0.7f

/** The search field matches the home screen's bar: the same height, glass, and quiet white. */
private val FIELD_HEIGHT = 48.dp
private val GLASS = 24.dp
private const val INK_ALPHA = 0.85f
private const val HINT_ALPHA = 0.5f

/**
 * Every launchable app, alphabetically, on an opaque surface, under a search field that narrows the
 * list as you type ([query]) and takes the keyboard as the drawer opens when [keyboard] says so.
 * Icons are the home screen's [iconSize] unless [columns] leaves less room than that. With nothing
 * typed and [index] set, an alphabet down the right edge jumps the list. Drawn [shown] of the way
 * up from the bottom edge, its top edge under the finger once it has caught up with it, translucent
 * while it arrives. Closes on back, and pulling the list down past its top pulls the drawer down
 * with it; letting go decides ([onPullEnd]). Launching an app closes it.
 */
@Composable
fun AppDrawer(
    shown: State<Float>,
    open: Boolean,
    apps: List<AppEntry>,
    folders: List<HomeItem>,
    query: DrawerQuery,
    keyboard: Boolean,
    index: Boolean,
    columns: Int,
    iconSize: Dp,
    labels: Boolean,
    insets: PaddingValues,
    onPull: (dy: Float) -> Unit,
    onPullEnd: (velocity: Float) -> Unit,
    onLaunch: (AppEntry) -> Unit,
    onOpenFolder: (HomeItem, Bounds) -> Unit,
    onClose: () -> Unit,
    drag: DragSession?,
) {
    BackHandler(enabled = open, onBack = onClose)
    // Stays composed while open even when pulled fully down, so the gesture that pulled it can
    // finish and decide; only a closed drawer with nothing showing is gone.
    // Present while open, or while any of it shows; decided from state so a pull's frames do
    // not recompose the drawer, only redraw it.
    val present by remember(open) { derivedStateOf { open || shown.value > 0f } }
    if (!present) return
    val pull = rememberUpdatedState(onPull)
    val pullEnd = rememberUpdatedState(onPullEnd)
    val list = rememberLazyGridState()
    val pullToClose = remember {
        PullToClose(
            revealed = { shown.value },
            atTop = { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0 },
            onPull = { pull.value(it) },
            onPullEnd = { pullEnd.value(it) },
        )
    }
    // Apps in drawer folders are reached through them, or by search, not from the grid.
    val searching = query.text.isNotBlank()
    val shownFolders = if (searching) emptyList() else folders
    val shownApps =
        remember(apps, folders, query.text) {
            if (searching) {
                apps.matching(query.text)
            } else {
                val folded = folders.flatMapTo(HashSet()) { f -> f.folder.map { it.key } }
                apps.filter { it.key !in folded }
            }
        }
    BoxWithConstraints(
        Modifier.fillMaxSize().arriving { shown.value }.background(SURFACE).testTag(DRAWER_TAG)
    ) {
        val icon = fittedIconSize(iconSize, maxWidth, insets, columns)
        val keyboardHeight = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
        Column(Modifier.fillMaxSize()) {
            SearchField(
                query,
                keyboard,
                open,
                Modifier.padding(top = insets.calculateTopPadding() + MARGIN / 2)
                    .padding(horizontal = MARGIN),
            )
            DrawerGrid(
                shownFolders,
                shownApps,
                list,
                indexed = index && !searching,
                columns = columns,
                iconSize = icon,
                labels = labels,
                contentPadding = insets.aboveKeyboard(keyboardHeight, indexed = index),
                above = keyboardHeight,
                pullToClose = pullToClose,
                onLaunch = onLaunch,
                onOpenFolder = onOpenFolder,
                drag = drag,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** The folder section, then the apps, in one grid; the index over its right edge when [indexed]. */
@Composable
@Suppress("LongParameterList") // one row of the drawer's layout, not a design of its own
private fun DrawerGrid(
    folders: List<HomeItem>,
    apps: List<AppEntry>,
    state: LazyGridState,
    indexed: Boolean,
    columns: Int,
    iconSize: Dp,
    labels: Boolean,
    contentPadding: PaddingValues,
    above: Dp,
    pullToClose: NestedScrollConnection,
    onLaunch: (AppEntry) -> Unit,
    onOpenFolder: (HomeItem, Bounds) -> Unit,
    drag: DragSession?,
    modifier: Modifier,
) {
    val entries =
        remember(apps, folders.size) {
            apps.index().map { IndexEntry(it.letter, it.position + folders.size) }
        }
    IndexedGrid(entries, state, indexed, above, modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = it,
            // Insets pad the content, not the grid: its scrollable then covers the whole screen,
            // so a pull that starts at the edge still pulls.
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize().testTag(DRAWER_LIST_TAG).nestedScroll(pullToClose),
        ) {
            items(folders, key = { "folder:${it.id}" }) { folder ->
                DrawerFolder(
                    folder,
                    iconSize,
                    labels,
                    onOpen = { cell -> onOpenFolder(folder, cell) },
                    drag = drag?.handlersForDrawerFolder(folder),
                )
            }
            items(apps, key = { it.key }) { app ->
                DrawerApp(
                    app,
                    iconSize,
                    labels,
                    onClick = { onLaunch(app) },
                    drag = drag?.handlersForDrawer(app),
                )
            }
        }
    }
}

/**
 * The grid, with the alphabet index of [entries] over its right edge when [indexed], centred in the
 * part of the grid not covered from below: [above] is the keyboard's height while it shows, so the
 * index sits above it, in reach, rather than half under it.
 */
@Composable
internal fun IndexedGrid(
    entries: List<IndexEntry>,
    state: LazyGridState,
    indexed: Boolean,
    above: Dp,
    modifier: Modifier,
    content: @Composable (LazyGridState) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxWidth()) {
        content(state)
        if (indexed) {
            DrawerIndex(
                entries,
                onJump = { position -> scope.launch { state.scrollToItem(position) } },
                modifier = Modifier.align(Alignment.CenterEnd).padding(bottom = above),
            )
        }
    }
}

/**
 * Drawn [shown] of the way up from the bottom, translucent on the way. Not scaled: its top edge is
 * where the finger is, and must stay there.
 */
private fun Modifier.arriving(revealed: () -> Float) = graphicsLayer {
    alpha = (FAINTEST + (1f - FAINTEST) * revealed() / OPAQUE_AT).coerceAtMost(1f)
    translationY = (1f - revealed()) * size.height
}

/** The home screen's icon size, unless [columns] across the room inside the margins is tighter. */
@Composable
private fun fittedIconSize(iconSize: Dp, width: Dp, insets: PaddingValues, columns: Int): Dp {
    val direction = LocalLayoutDirection.current
    val room =
        width -
            insets.calculateStartPadding(direction) -
            insets.calculateEndPadding(direction) -
            MARGIN * 2
    return minOf(iconSize, room / columns * ICON_SHARE)
}

/**
 * The list's padding: the margin inside the system bars at the sides, a little under the field, and
 * at the bottom the [keyboard]'s height while it shows, else the bar's.
 */
@Composable
private fun PaddingValues.aboveKeyboard(keyboard: Dp, indexed: Boolean): PaddingValues {
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(direction) + MARGIN,
        top = MARGIN / 2,
        end = calculateEndPadding(direction) + MARGIN + if (indexed) INDEX_WIDTH else 0.dp,
        bottom = maxOf(calculateBottomPadding(), keyboard) + MARGIN,
    )
}

/**
 * Narrows the list as you type; Enter launches the best match. Takes focus, and with it the
 * keyboard, as the drawer opens when [keyboard] is set; the field is still there to tap otherwise.
 */
@Composable
private fun SearchField(query: DrawerQuery, keyboard: Boolean, open: Boolean, modifier: Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(open, keyboard) { if (open && keyboard) focus.requestFocus() }
    val ink = Color.White.copy(alpha = INK_ALPHA)
    val style = MaterialTheme.typography.titleMedium.copy(color = ink)
    Row(
        modifier = modifier.fillMaxWidth().height(FIELD_HEIGHT).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = null,
            tint = ink,
            modifier = Modifier.size(GLASS),
        )
        BasicTextField(
            value = query.text,
            onValueChange = query.onChange,
            singleLine = true,
            textStyle = style,
            cursorBrush = SolidColor(ink),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { query.onSubmit() }),
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (query.text.isEmpty()) {
                        Text(
                            "Search apps",
                            style = style.copy(color = ink.copy(alpha = HINT_ALPHA)),
                        )
                    }
                    field()
                }
            },
            modifier =
                Modifier.padding(start = 12.dp)
                    .weight(1f)
                    .focusRequester(focus)
                    .testTag(DRAWER_SEARCH_TAG),
        )
    }
}

/** A drawer folder's tile: its first apps on the folder square, over its name. */
@Composable
private fun DrawerFolder(
    folder: HomeItem,
    iconSize: Dp,
    labels: Boolean,
    onOpen: (Bounds) -> Unit,
    drag: DragHandlers?,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    AppTile(
        folder.label,
        labelled = labels,
        Modifier.onGloballyPositioned { coords = it }
            .clickable { onOpen(coords?.boundsInRoot()?.toBounds() ?: Bounds(0f, 0f, 0f, 0f)) }
            .liftable("folder:${folder.id}", drag)
            .padding(vertical = 8.dp)
            .testTag(DRAWER_FOLDER_TAG),
    ) {
        FolderIcon(folder.folder, folder.label, Modifier.size(iconSize))
    }
}

@Composable
private fun DrawerApp(
    app: AppEntry,
    iconSize: Dp,
    labels: Boolean,
    onClick: () -> Unit,
    drag: DragHandlers?,
) {
    AppTile(
        app,
        iconSize,
        labels,
        onClick,
        Modifier.liftable(app.key, drag).padding(vertical = 8.dp).testTag(DRAWER_ITEM_TAG),
    )
}

/**
 * A swipe that begins with the list at its top pulls the drawer down instead of scrolling, and back
 * up again while it is part way; letting go reports the velocity. A swipe that merely scrolls the
 * list back to its top stops there: its leftover, and its fling, are not a pull. What the pull
 * takes, it consumes: the list's stretch at its edge is for scrolling past the top, not for this,
 * and the finger's own movement is the pull, never what a fling leaves over after it lifts.
 */
internal class PullToClose(
    private val revealed: () -> Float,
    private val atTop: () -> Boolean,
    private val onPull: (Float) -> Unit,
    private val onPullEnd: (Float) -> Unit,
) : NestedScrollConnection {
    /** Whether the swipe in progress may pull: decided as it begins, from where the list was. */
    private var pulling: Boolean? = null

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        val pulls =
            pulling ?: (source == NestedScrollSource.UserInput && atTop()).also { pulling = it }
        if (pulls && revealed() < 1f && available.y < 0f) {
            onPull(available.y)
            return available
        }
        return Offset.Zero
    }

    override fun onPostScroll(
        consumed: Offset,
        available: Offset,
        source: NestedScrollSource,
    ): Offset {
        if (pulling != true || available.y <= 0f) return Offset.Zero
        // Once the finger is up, the list's fling may still have leftover: that is nobody's pull,
        // but the stretch is not for it either.
        if (source == NestedScrollSource.UserInput) onPull(available.y)
        return Offset(0f, available.y)
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        val pulled = pulling == true
        pulling = null
        if (!pulled) return Velocity.Zero
        onPullEnd(available.y)
        return available
    }
}
