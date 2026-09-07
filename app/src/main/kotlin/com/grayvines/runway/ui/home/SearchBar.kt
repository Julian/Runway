package com.grayvines.runway.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.search.SearchTarget

/** The bar is [BAR_HEIGHT] tall inside its grid row: no surface, just what is on it. */
private val BAR_HEIGHT = 48.dp
private val GLASS = 24.dp

/** Everything on the bar is white at this alpha: present, but quieter than the icons below. */
private const val BAR_ALPHA = 0.85f

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
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Color.White.copy(alpha = BAR_ALPHA)
    Box(
        modifier = modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(BAR_HEIGHT)
                    .clip(CircleShape)
                    .clickable(
                        onClickLabel = target?.let { "Search with ${it.label}" } ?: "Search",
                        role = Role.Button,
                        onClick = onSearch,
                    )
                    .testTag(SEARCH_BAR_TAG)
                    .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Search,
                contentDescription = target?.label,
                tint = ink,
                modifier = Modifier.size(GLASS).testTag(SEARCH_TARGET_ICON_TAG),
            )
            Text(
                text = "Search",
                color = ink,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 12.dp).weight(1f),
            )
            IconButton(onClick = onMenu, modifier = Modifier.testTag(SEARCH_MENU_TAG)) {
                Icon(Icons.Outlined.MoreVert, contentDescription = "Runway settings", tint = ink)
            }
        }
    }
}
