package com.grayvines.runway.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grayvines.runway.data.AppRef
import com.grayvines.runway.data.settings.BadgeStyle
import com.grayvines.runway.model.PackageKey
import com.grayvines.runway.model.badgeCountForApp
import com.grayvines.runway.model.badgeCountForFolder
import com.grayvines.runway.model.formatBadgeCount

const val BADGE_TAG = "badge"

private val BADGE_COLOR = Color(0xFF8AB4F8)
private val BADGE_TEXT_COLOR = Color(0xFF1F1F1F)
private val DOT_SIZE = 10.dp
private val PILL_MIN_SIZE = 18.dp
private val PILL_HORIZONTAL_PADDING = 5.dp
private val PILL_VERTICAL_PADDING = 1.dp

/**
 * The notification badge counts, muted apps, and chosen style for the whole launcher surface.
 * Provided once at the root so every icon and folder tile can draw its badge without threading the
 * counts through every grid and sheet.
 */
@Immutable
data class BadgeState(
    val style: BadgeStyle = BadgeStyle.COUNT,
    val counts: Map<PackageKey, Int> = emptyMap(),
    val muted: Set<AppRef> = emptySet(),
)

internal fun BadgeState.countFor(app: AppRef): Int = badgeCountForApp(app, counts, muted)

internal fun BadgeState.countFor(apps: Iterable<AppRef>): Int =
    badgeCountForFolder(apps, counts, muted)

internal val LocalBadges = compositionLocalOf { BadgeState() }

/**
 * A notification badge at the top-right corner of an icon or folder tile: a count pill ("99+" above
 * 99), a dot, or nothing when [count] is not positive or [style] is [BadgeStyle.OFF].
 */
@Composable
internal fun Badge(count: Int, style: BadgeStyle, modifier: Modifier = Modifier) {
    if (count <= 0 || style == BadgeStyle.OFF) return
    when (style) {
        BadgeStyle.DOT ->
            Box(modifier.size(DOT_SIZE).background(BADGE_COLOR, CircleShape).testTag(BADGE_TAG))
        BadgeStyle.COUNT ->
            Box(
                modifier
                    .defaultMinSize(minWidth = PILL_MIN_SIZE, minHeight = PILL_MIN_SIZE)
                    .background(BADGE_COLOR, RoundedCornerShape(percent = 50))
                    .padding(
                        horizontal = PILL_HORIZONTAL_PADDING,
                        vertical = PILL_VERTICAL_PADDING,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = formatBadgeCount(count),
                    color = BADGE_TEXT_COLOR,
                    style =
                        MaterialTheme.typography.labelSmall.copy(
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag(BADGE_TAG),
                )
            }
        BadgeStyle.OFF -> Unit
    }
}
