package com.grayvines.runway.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/** A search row is this tall: no surface, just what is on it. */
val SEARCH_ROW_HEIGHT = 48.dp

private val GLASS = 24.dp

/** Everything on a search row is white at this alpha: present, but quieter than the icons. */
private const val INK_ALPHA = 0.85f

/** An empty field's hint is fainter still than what is typed. */
private const val HINT_ALPHA = 0.5f

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

/**
 * A search row whose content is a field: [text] in the row's ink, and [hint] faintly in its place
 * while nothing is typed. [fieldModifier] is the field's, for focus and a tag.
 */
@Composable
fun SearchRowField(
    text: String,
    onChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    SearchRow(glassDescription = null, modifier = modifier) { ink ->
        BasicTextField(
            value = text,
            onValueChange = onChange,
            singleLine = true,
            textStyle = ink.style,
            cursorBrush = SolidColor(ink.color),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            decorationBox = { field ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text(
                            hint,
                            style = ink.style.copy(color = ink.color.copy(alpha = HINT_ALPHA)),
                        )
                    }
                    field()
                }
            },
            modifier = Modifier.padding(start = 12.dp).weight(1f).then(fieldModifier),
        )
    }
}
