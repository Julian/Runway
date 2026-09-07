package com.grayvines.runway.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.core.graphics.drawable.toBitmap
import com.grayvines.runway.system.search.SearchTarget

/** The pill's height as a fraction of its grid row, capped so sparse grids don't balloon it. */
private const val PILL_HEIGHT = 0.6f
private val PILL_MAX_HEIGHT = 64.dp
/** The target's icon height as a fraction of the pill. */
private const val PILL_ICON_HEIGHT = 0.55f
/** Slightly faded so the pill reads as a control, not another app icon. */
private const val PILL_ICON_ALPHA = 0.75f
private const val ICON_BITMAP_SIZE = 128

const val SEARCH_BAR_TAG = "search-bar"
const val SEARCH_TARGET_ICON_TAG = "search-target-icon"

/** A pill showing the app that will handle the search; a tap hands off to it. */
@Composable
fun SearchBar(
    rowHeight: Dp,
    target: SearchTarget?,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxWidth().height(rowHeight).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .height(min(rowHeight * PILL_HEIGHT, PILL_MAX_HEIGHT))
                    .background(Scrim, CircleShape)
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
            if (target != null) {
                val bitmap =
                    remember(target.packageName) {
                        target.icon.toBitmap(ICON_BITMAP_SIZE, ICON_BITMAP_SIZE).asImageBitmap()
                    }
                Image(
                    bitmap = bitmap,
                    contentDescription = target.label,
                    modifier =
                        Modifier.fillMaxHeight(PILL_ICON_HEIGHT)
                            .aspectRatio(1f)
                            .testTag(SEARCH_TARGET_ICON_TAG),
                    alpha = PILL_ICON_ALPHA,
                )
            }
            Text(
                text = "Search",
                color = Color.White.copy(alpha = PILL_ICON_ALPHA),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp),
            )
        }
    }
}

/** Translucent surface colour used over the wallpaper. */
val Scrim: Color = Color.Black.copy(alpha = 0.35f)
