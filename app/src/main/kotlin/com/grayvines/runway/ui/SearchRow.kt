package com.grayvines.runway.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** A search row is this tall: no surface, just what is on it. */
val SEARCH_ROW_HEIGHT = 48.dp

private val GLASS = 24.dp

/** Everything on a search row is white at this alpha: present, but quieter than the icons. */
private const val INK_ALPHA = 0.85f

/** What a search row has to say in [Ink]: the colour, and the text style in that colour. */
class Ink(val color: Color, val style: TextStyle)

/**
 * The one look of a search row, on the home screen and in the drawer: a magnifying glass at the
 * start, described by [glassDescription], then [content] filling the rest, drawn in the row's
 * [Ink]. The glass carries [glassModifier], for a tag.
 */
@Composable
fun SearchRow(
    glassDescription: String?,
    modifier: Modifier = Modifier,
    glassModifier: Modifier = Modifier,
    content: @Composable RowScope.(Ink) -> Unit,
) {
    val color = Color.White.copy(alpha = INK_ALPHA)
    val ink = Ink(color, MaterialTheme.typography.titleMedium.copy(color = color))
    Row(
        modifier = modifier.fillMaxWidth().height(SEARCH_ROW_HEIGHT).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.Search,
            contentDescription = glassDescription,
            tint = color,
            modifier = glassModifier.size(GLASS),
        )
        content(ink)
    }
}
