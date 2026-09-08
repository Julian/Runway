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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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

const val FOLDER_TAG = "folder"
const val FOLDER_ITEM_TAG = "folder-app"
const val FOLDER_NAME_TAG = "folder-name"

/** Long enough for any name that fits a tile's label; short enough to stay a name. */
private const val MAX_NAME_LENGTH = 40

private val SURFACE = Color(0xFF202124)
private val CORNER = 24.dp
private val CELL = 84.dp
private const val MAX_COLUMNS = 4

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
 * when closed. Tapping an app launches it; a long press lifts it out; tapping the name edits it; a
 * tap anywhere else, or back, closes the folder.
 */
@Composable
fun FolderSheet(
    folder: HomeItem,
    from: Bounds,
    iconSize: Dp,
    actions: FolderActions,
    drag: DragSession?,
) {
    val motion = rememberSheetMotion(actions.close)
    BackHandler(onBack = motion.close)
    var room by remember { mutableStateOf(IntSize.Zero) }
    // Above the keyboard while the name is being typed, centred otherwise.
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
        val columns = folder.folder.size.coerceIn(1, MAX_COLUMNS)
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
            Column(Modifier.padding(16.dp).graphicsLayer { alpha = motion.progress }) {
                FolderName(folder.label, actions.rename)
                LazyVerticalGrid(columns = GridCells.Fixed(columns)) {
                    items(folder.folder, key = { it.key }) { app ->
                        FolderApp(
                            app,
                            iconSize,
                            onClick = { actions.launch(app) },
                            drag =
                                drag?.handlersForFolder(
                                    app,
                                    leaving = folder.folderId.takeUnless { folder.inDrawer },
                                ),
                        )
                    }
                }
            }
        }
    }
}

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
 * The folder's name; a tap turns it into a field with the keyboard up. Done, or leaving the field,
 * keeps what was typed (a blank name is not kept).
 */
@Composable
private fun FolderName(name: String, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var text by remember(name) { mutableStateOf(name) }
    val modifier = Modifier.padding(bottom = 12.dp).testTag(FOLDER_NAME_TAG)
    if (!editing) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = modifier.clickable { editing = true },
        )
        return
    }
    val commit = {
        editing = false
        if (text != name) onRename(text)
    }
    val focus = remember { FocusRequester() }
    var hadFocus by remember { mutableStateOf(false) }
    BasicTextField(
        value = text,
        onValueChange = { text = it.take(MAX_NAME_LENGTH) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleMedium.copy(color = Color.White),
        cursorBrush = SolidColor(Color.White),
        keyboardOptions =
            KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier =
            modifier.focusRequester(focus).onFocusChanged {
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
        Modifier.liftable(app.key, drag).padding(vertical = 8.dp).testTag(FOLDER_ITEM_TAG),
    )
}
