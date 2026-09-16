package com.grayvines.runway.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

const val FOLDER_EDIT_TAG = "folder-edit"
const val FOLDER_REMOVE_TAG = "folder-remove"

/** Lighter than the sheet, which is the widget frame's chip colour: a chip must stand off it. */
private val CHIP = Color(0xFF5F6368)

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
            Modifier.size(REMOVE_SIZE).background(CHIP, CircleShape),
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
