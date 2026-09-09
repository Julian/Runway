package com.grayvines.runway.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.search.SearchTarget
import com.grayvines.runway.ui.SearchRow
import com.grayvines.runway.ui.drag.Bounds

const val SEARCH_BAR_TAG = "search-bar"
const val SEARCH_TARGET_ICON_TAG = "search-target-icon"
const val SEARCH_MENU_TAG = "search-menu"

/**
 * A transparent bar: a magnifying glass, the word "Search", and a menu on the far side that opens
 * Runway's settings. A tap on the bar hands off to the search app; the glass is described by that
 * app's name, so what a tap does is said out loud and can be checked.
 */
@Composable
fun SearchBar(
    rowHeight: Dp,
    target: SearchTarget?,
    onSearch: () -> Unit,
    /** The three dots were tapped; where they are (root px), for a menu to sit under. */
    onMenu: (Bounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        SearchRow(
            glassDescription = target?.label,
            modifier =
                Modifier.clip(CircleShape)
                    .clickable(
                        onClickLabel = target?.let { "Search with ${it.label}" } ?: "Search",
                        role = Role.Button,
                        onClick = onSearch,
                    )
                    .testTag(SEARCH_BAR_TAG),
            glassModifier = Modifier.testTag(SEARCH_TARGET_ICON_TAG),
        ) { ink ->
            Text(
                text = "Search",
                style = ink.style,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            var dots by remember { mutableStateOf<LayoutCoordinates?>(null) }
            IconButton(
                onClick = { onMenu(dots?.boundsInRoot()?.toBounds() ?: Bounds(0f, 0f, 0f, 0f)) },
                modifier = Modifier.onGloballyPositioned { dots = it }.testTag(SEARCH_MENU_TAG),
            ) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Runway menu", tint = ink.color)
            }
        }
    }
}
