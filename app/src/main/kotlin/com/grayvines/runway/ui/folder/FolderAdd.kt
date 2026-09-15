package com.grayvines.runway.ui.folder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.ui.SearchRowField
import com.grayvines.runway.ui.drawer.matching
import com.grayvines.runway.ui.home.AppTile

const val FOLDER_ADD_TAG = "folder-add"
const val FOLDER_CANDIDATE_TAG = "folder-candidate"
const val FOLDER_ADD_SEARCH_TAG = "folder-add-search"

/** Room for a few rows of apps to choose from, and still a sheet rather than a screen. */
private val LIST_HEIGHT = 360.dp

/** The plus tile is dimmer than a folder's: it is a way in, not something in the folder. */
private const val ADD_TILE_ALPHA = 0.12f
private const val PLUS_SHARE = 0.5f

/** A list with nothing in it says so, quieter than the apps it would show. */
private const val EMPTY_ALPHA = 0.6f

/** A drawer folder's last tile: a plus on a dim square, which turns the sheet to adding apps. */
@Composable
internal fun AddTile(iconSize: Dp, onClick: () -> Unit) {
    AppTile(
        "Add",
        labelled = true,
        Modifier.clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag(FOLDER_ADD_TAG),
    ) {
        Box(
            Modifier.size(iconSize)
                .clip(RoundedCornerShape(percent = 25))
                .background(Color.White.copy(alpha = ADD_TILE_ALPHA)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Add,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.fillMaxSize(PLUS_SHARE),
            )
        }
    }
}

/**
 * A drawer folder's sheet while adding: its [name] and Done over a search field and every app of
 * [apps] not already [inFolder], in the order given, narrowed by what is typed. A tap adds the app
 * at once and the list stays up for the next; Done goes back to the folder.
 */
@Composable
internal fun AddApps(
    name: String,
    inFolder: List<AppEntry>,
    apps: List<AppEntry>,
    iconSize: Dp,
    onAdd: (AppEntry) -> Unit,
    onDone: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val candidates =
        remember(apps, inFolder, query) {
            val inside = inFolder.mapTo(HashSet()) { it.key }
            apps.filter { it.key !in inside }.matching(query)
        }
    val focusManager = LocalFocusManager.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onDone,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            ) {
                Text("Done")
            }
        }
        SearchRowField(
            text = query,
            onChange = { query = it },
            hint = "Search apps",
            fieldModifier = Modifier.testTag(FOLDER_ADD_SEARCH_TAG),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        )
        // One height whatever is listed, so the sheet holds still while the list narrows.
        Box(Modifier.fillMaxWidth().height(LIST_HEIGHT), contentAlignment = Alignment.Center) {
            if (candidates.isEmpty()) {
                Text(
                    if (query.isBlank()) "Every app is in this folder" else "No apps match",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = EMPTY_ALPHA),
                )
            } else {
                LazyVerticalGrid(GridCells.Fixed(MAX_COLUMNS), Modifier.fillMaxSize()) {
                    items(candidates, key = { it.key }) { app ->
                        AppTile(
                            app,
                            iconSize,
                            labelled = true,
                            onClick = { onAdd(app) },
                            Modifier.padding(vertical = 8.dp).testTag(FOLDER_CANDIDATE_TAG),
                        )
                    }
                }
            }
        }
    }
}
