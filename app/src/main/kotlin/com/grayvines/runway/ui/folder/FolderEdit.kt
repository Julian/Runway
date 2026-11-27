package com.grayvines.runway.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

const val FOLDER_EDIT_TAG = "folder-edit"
const val FOLDER_REMOVE_TAG = "folder-remove"
const val FOLDER_NAME_TAG = "folder-name"

/** Long enough for any name that fits a tile's label; short enough to stay a name. */
private const val MAX_NAME_LENGTH = 40

/** Lighter than the sheet, which is the widget frame's chip colour: a chip must stand off it. */
private val CHIP = Color(0xFF5F6368)
private val REMOVE_CHIP = Color(0xFFD93025)

/** Smaller than an app's x: the pencil is the way in, not one of the folder's own things. */
private val TOGGLE_SIZE = 22.dp
private val TOGGLE_ICON = 14.dp
private val TOGGLE_TOUCH = 40.dp

/** The widget frame's Remove, in size. */
internal val REMOVE_SIZE = 28.dp
private val REMOVE_ICON = 18.dp

/** A finger's worth around the chip, more than the chip's own size. */
internal val REMOVE_TOUCH = 40.dp

/**
 * The pencil at the sheet's top right, a check while [editing]; a tap turns editing on or off,
 * which is when the folder shows its x's and, in the drawer, its plus.
 */
@Composable
internal fun EditToggle(editing: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            // A finger's worth to tap, hanging off the sheet's padding on its outer side rather
            // than holding so small a pencil that far in from the corner.
            .offset(x = (TOGGLE_TOUCH - TOGGLE_SIZE) / 2)
            .size(TOGGLE_TOUCH)
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { contentDescription = if (editing) "Done editing" else "Edit folder" }
            .testTag(FOLDER_EDIT_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(TOGGLE_SIZE).background(CHIP, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (editing) Icons.Outlined.Check else Icons.Outlined.Edit,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(TOGGLE_ICON),
            )
        }
    }
}

/** The x on an app while the folder is edited: a tap takes the app out, there and then. */
@Composable
internal fun RemoveChip(label: String, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(REMOVE_TOUCH)
            .clickable(role = Role.Button, onClick = onRemove)
            .semantics { contentDescription = "Remove $label" }
            .testTag(FOLDER_REMOVE_TAG),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(REMOVE_SIZE).background(REMOVE_CHIP, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Clear,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(REMOVE_ICON),
            )
        }
    }
}

/**
 * The folder's name; a tap turns it into a field with the keyboard up. Done, leaving the field, or
 * closing the folder keeps what was typed. A blank name is no name: Done puts the old one back and
 * leaves the field open, so the refusal is seen rather than guessed at; closing on a blank keeps
 * the old name, there being no field left to show the refusal in.
 */
@Composable
internal fun FolderName(name: String, modifier: Modifier, onRename: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val tagged = modifier.testTag(FOLDER_NAME_TAG)
    if (!editing) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                tagged.clickable(role = Role.Button, onClickLabel = "Rename folder") {
                    editing = true
                },
        )
        return
    }
    FolderNameField(name, tagged) { text ->
        editing = false
        if (text != name && text.isNotBlank()) onRename(text)
    }
}

@Composable
private fun FolderNameField(name: String, modifier: Modifier, onFinish: (String) -> Unit) {
    // The whole name selected as the field opens: what is typed replaces it, and a keep is a tap
    // away, which is what a tap on a name usually means.
    var field by remember(name) { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val currentName by rememberUpdatedState(name)
    val currentOnFinish by rememberUpdatedState(onFinish)
    var committed by remember { mutableStateOf(false) }
    val commit = {
        if (!committed) {
            committed = true
            currentOnFinish(field.text)
        }
    }
    val currentCommit by rememberUpdatedState(commit)
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
                        field = TextFieldValue(currentName, TextRange(0, currentName.length))
                    } else {
                        commit()
                    }
                }
            ),
        modifier =
            modifier.focusRequester(focus).onFocusChanged {
                if (it.isFocused) hadFocus = true else if (hadFocus) commit()
            },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
    // Closing the folder mid-edit (a tap outside, back) keeps the name too.
    DisposableEffect(Unit) { onDispose { currentCommit() } }
}
