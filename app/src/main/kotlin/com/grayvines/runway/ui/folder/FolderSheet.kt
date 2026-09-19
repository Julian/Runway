package com.grayvines.runway.ui.folder

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.home.AppTile
import com.grayvines.runway.ui.home.DragHandlers
import com.grayvines.runway.ui.home.DragSession
import com.grayvines.runway.ui.home.HomeItem
import com.grayvines.runway.ui.home.liftable
import com.grayvines.runway.ui.home.rememberLiftConfiguration

const val FOLDER_TAG = "folder"
const val FOLDER_ITEM_TAG = "folder-app"
const val FOLDER_NAME_TAG = "folder-name"

/** Long enough for any name that fits a tile's label; short enough to stay a name. */
private const val MAX_NAME_LENGTH = 40

private val SURFACE = Color(0xFF202124)
private val CORNER = 24.dp
private val CELL = 84.dp
internal const val MAX_COLUMNS = 4
private const val MIN_COLUMNS = 2

/** Above and below each app's tile, inside its grid cell. */
private val TILE_PADDING = 8.dp

/** How far inside the icon's corner the middle of its x sits. */
private val CHIP_INSET = 6.dp

/** The add tile's key among the apps' keys, which are a profile and a component. */
private const val ADD_KEY = "add"

/** How much of the screen the scrim behind the folder darkens. */
private const val SCRIM_ALPHA = 0.4f

/**
 * Over the first part of the motion the panel is see-through, so what shows at the tile is the
 * tile, not a dark square the panel's size; by this far in it is solid.
 */
private const val SOLID_AT = 0.35f
private val SHADOW = 16.dp

/**
 * An open folder: its name over a grid of its apps, sized to what it holds. It grows out of the
 * cell it was tapped in ([from], root px) to the middle of the screen, and shrinks back into it
 * when closed. Tapping an app launches it; a long press shows its menu, and moving on lifts it out;
 * tapping the name edits it. The pencil at the top right puts an x on every app, which takes that
 * app out, and in a drawer folder a plus after them, which lists the rest of [apps] to add. A
 * folder emptied by its x's stays open, and goes only once it is closed empty. Back leaves the
 * list, then the editing; a tap anywhere else, or back, closes the folder.
 */
@Composable
fun FolderSheet(
    folder: HomeItem,
    from: Bounds,
    iconSize: Dp,
    /** The drawer's apps, in its order: what a drawer folder can add. */
    apps: List<AppEntry>,
    actions: FolderActions,
    drag: DragSession?,
    /** Asked to go (an app was dragged out): the sheet runs its close motion, then closes. */
    leaving: Boolean = false,
) {
    val motion = rememberSheetMotion(actions.close)
    BackHandler(onBack = motion.close)
    val mode = remember { SheetMode() }
    val listing = mode.adding && folder.addTo != null
    // After the sheet's own, so while the list or the x's are up it is the one Back reaches.
    BackHandler(enabled = listing || mode.editing) {
        if (listing) mode.adding = false else mode.editing = false
    }
    LaunchedEffect(leaving) { if (leaving) motion.close() }
    // Whatever had the keyboard (the drawer's search field) gives it up as the sheet opens: what
    // is typed now is for the folder, not for a field hidden behind it.
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { focusManager.clearFocus() }
    var room by remember { mutableStateOf(IntSize.Zero) }
    // Above the keyboard while something is typed (the name, or a search for apps to add),
    // centred otherwise.
    Box(
        Modifier.fillMaxSize().imePadding().onSizeChanged { room = it },
        contentAlignment = Alignment.Center,
    ) {
        // A sibling, not a parent: a clickable parent would merge the sheet's semantics into it.
        Spacer(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = motion.progress * SCRIM_ALPHA }
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = motion.close,
                )
        )
        val columns = columnsFor(folder, listing, mode.editing)
        var size by remember { mutableStateOf(IntSize.Zero) }
        val solid = (motion.progress / SOLID_AT).coerceIn(0f, 1f)
        Surface(
            modifier =
                Modifier.width(CELL * columns + 32.dp)
                    .onSizeChanged { size = it }
                    .growingFrom(from, room, size) { motion.progress }
                    .graphicsLayer { alpha = solid }
                    .testTag(FOLDER_TAG),
            shape = RoundedCornerShape(CORNER),
            color = SURFACE,
            contentColor = Color.White,
            shadowElevation = SHADOW * solid,
        ) {
            SheetContent(
                folder,
                apps,
                iconSize,
                columns,
                actions,
                drag,
                mode,
                modifier = Modifier.padding(16.dp).graphicsLayer { alpha = motion.progress },
            )
        }
    }
}

/**
 * A drawer folder's id: it takes apps from a list. Null for any other, which takes them by a drop.
 */
private val HomeItem.addTo: Long?
    get() = folderId?.takeIf { inDrawer }

/**
 * Whether the plus is among the tiles: while the folder is edited, which is when apps go in or come
 * out, and in an empty folder, which would otherwise be a sheet with nothing to tap.
 */
private fun HomeItem.showsAdd(editing: Boolean) = addTo != null && (editing || folder.isEmpty())

/**
 * As many columns as the folder has tiles, its plus included, up to four, and never fewer than two:
 * the name shares its row with the pencil. Four for the list.
 */
private fun columnsFor(folder: HomeItem, listing: Boolean, editing: Boolean): Int {
    val tiles = folder.folder.size + if (folder.showsAdd(editing)) 1 else 0
    return if (listing) MAX_COLUMNS else tiles.coerceIn(MIN_COLUMNS, MAX_COLUMNS)
}

/** What the sheet shows over its apps: the list of apps to add, or an x on each of its own. */
private class SheetMode {
    var adding by mutableStateOf(false)
    var editing by mutableStateOf(false)
}

/**
 * The folder's name and pencil over its apps, which while it is edited end in a plus for a drawer
 * folder; or, while [mode] is adding, the apps that folder can add.
 */
@Composable
private fun SheetContent(
    folder: HomeItem,
    apps: List<AppEntry>,
    iconSize: Dp,
    columns: Int,
    actions: FolderActions,
    drag: DragSession?,
    mode: SheetMode,
    modifier: Modifier,
) {
    val addTo = folder.addTo
    Column(modifier) {
        if (addTo != null && mode.adding) {
            AddApps(
                folder.label,
                folder.folder,
                apps,
                iconSize,
                onAdd = { actions.add(addTo, it) },
                onDone = { mode.adding = false },
            )
        } else {
            Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                FolderName(folder.label, Modifier.weight(1f)) { name ->
                    folder.folderId?.let { actions.rename(it, name) }
                }
                EditToggle(mode.editing, onToggle = { mode.editing = !mode.editing })
            }
            FolderGrid(folder, columns, iconSize, actions, drag, mode.editing) {
                if (addTo != null && folder.showsAdd(mode.editing)) {
                    item(key = ADD_KEY) { AddTile(iconSize) { mode.adding = true } }
                }
            }
        }
    }
}

/**
 * The folder's apps, then [after]. A tap launches an app and a hold shows its menu, or lifts the
 * app out of the folder; while [editing], an x on each takes it out instead, a tap on the app
 * itself does nothing, and a hold rearranges the folder rather than lifting anything out of it.
 */
@Composable
private fun FolderGrid(
    folder: HomeItem,
    columns: Int,
    iconSize: Dp,
    actions: FolderActions,
    drag: DragSession?,
    editing: Boolean,
    after: LazyGridScope.() -> Unit,
) {
    val order = rememberFolderOrder(folder.folder)
    val grid = rememberLazyGridState()
    val folderId = folder.folderId
    val rearranging =
        if (editing && folderId != null) {
            Modifier.rearranges(order, grid) { actions.reorder(folderId, it) }
        } else {
            Modifier
        }
    // The same deliberate hold as anywhere else an icon is picked up.
    CompositionLocalProvider(LocalViewConfiguration provides rememberLiftConfiguration()) {
        LazyVerticalGrid(columns = GridCells.Fixed(columns), state = grid, modifier = rearranging) {
            items(order.shown, key = { it.key }) { app ->
                // The held tile goes where the finger does; the rest spring to their new slots.
                val placed = if (order.held == app.key) Modifier else Modifier.animateItem()
                FolderTile(
                    app,
                    folder,
                    iconSize,
                    actions,
                    drag,
                    editing,
                    placed.slotted(app, order),
                )
            }
            after()
        }
    }
}

/** One app in the open folder: its tile, and while [editing] the x that takes it out. */
@Composable
private fun FolderTile(
    app: AppEntry,
    folder: HomeItem,
    iconSize: Dp,
    actions: FolderActions,
    drag: DragSession?,
    editing: Boolean,
    modifier: Modifier,
) {
    Box(modifier) {
        FolderApp(
            app,
            iconSize,
            onClick = { if (!editing) actions.launch(app) },
            drag = if (editing) null else drag?.handlersForFolder(app, folder),
        )
        val folderId = folder.folderId
        if (editing && folderId != null) {
            RemoveChip(
                app.label,
                onRemove = { actions.remove(folderId, app) },
                Modifier.align(Alignment.TopCenter).onIconCorner(iconSize),
            )
        }
    }
}

/**
 * Centres the chip a little inside the top-right corner of the icon (which sits [TILE_PADDING]
 * below the tile's top), so the chip's circle stays within the tile and the grid does not clip it.
 */
private fun Modifier.onIconCorner(iconSize: Dp) =
    offset(x = iconSize / 2 - CHIP_INSET, y = TILE_PADDING + CHIP_INSET - REMOVE_TOUCH / 2)

/**
 * At progress 0 the sheet is the size and place of the tapped cell; at 1 it rests where the layout
 * put it, in the middle of the [room]. In between it scales and slides.
 */
private fun Modifier.growingFrom(
    from: Bounds,
    room: IntSize,
    size: IntSize,
    progress: () -> Float,
) = graphicsLayer {
    if (size.width > 0 && size.height > 0 && room.width > 0) {
        val p = progress()
        val restX = room.width / 2f
        val restY = room.height / 2f
        val fromX = (from.left + from.right) / 2f
        val fromY = (from.top + from.bottom) / 2f
        scaleX = lerp(from.width / size.width, 1f, p)
        scaleY = lerp(from.height / size.height, 1f, p)
        translationX = lerp(fromX - restX, 0f, p)
        translationY = lerp(fromY - restY, 0f, p)
    }
}

/** Opens on arrival; closing runs the motion back before telling [onClose]. */
private class SheetMotion(val progress: Float, val close: () -> Unit)

@Composable
private fun rememberSheetMotion(onClose: () -> Unit): SheetMotion {
    val progress = remember { Animatable(0f) }
    var closing by remember { mutableStateOf(false) }
    val close = rememberUpdatedState(onClose)
    LaunchedEffect(closing) {
        if (closing) {
            progress.animateTo(0f, spring(stiffness = Spring.StiffnessMedium))
            close.value()
        } else {
            progress.animateTo(1f, spring(stiffness = Spring.StiffnessMediumLow))
        }
    }
    return SheetMotion(progress.value) { closing = true }
}

/**
 * The folder's name; a tap turns it into a field with the keyboard up. Done, leaving the field, or
 * closing the folder keeps what was typed. A blank name is no name: Done puts the old one back and
 * leaves the field open, so the refusal is seen rather than guessed at; closing on a blank keeps
 * the old name, there being no field left to show the refusal in.
 */
@Composable
private fun FolderName(name: String, modifier: Modifier, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    // The whole name selected as the field opens: what is typed replaces it, and a keep is a tap
    // away, which is what a tap on a name usually means.
    var field by remember(name) { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val tagged = modifier.testTag(FOLDER_NAME_TAG)
    if (!editing) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = tagged.clickable { editing = true },
        )
        return
    }
    // Reads the field as it is when called, not as it was when this was composed: the dispose
    // below is set up once, and must still see the final text.
    val commit = {
        editing = false
        val text = field.text
        if (text != name && text.isNotBlank()) onRename(text)
    }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    BasicTextField(
        value = field,
        onValueChange = { field = it.copy(text = it.text.take(MAX_NAME_LENGTH)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
        keyboardActions =
            KeyboardActions(
                onDone = {
                    if (field.text.isBlank()) {
                        field = TextFieldValue(name, TextRange(0, name.length))
                    } else {
                        commit()
                    }
                }
            ),
        modifier =
            tagged.focusRequester(focus).onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) commit()
            },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Closing the folder mid-edit (a tap outside, back) keeps the name too.
    DisposableEffect(Unit) { onDispose { if (editing) commit() } }
}

@Composable
private fun FolderApp(app: AppEntry, iconSize: Dp, onClick: () -> Unit, drag: DragHandlers?) {
    AppTile(
        app,
        iconSize,
        labelled = true,
        onClick,
        Modifier.liftable(app.key, drag).padding(vertical = TILE_PADDING).testTag(FOLDER_ITEM_TAG),
    )
}
