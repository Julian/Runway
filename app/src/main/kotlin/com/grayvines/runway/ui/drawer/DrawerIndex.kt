package com.grayvines.runway.ui.drawer

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.apps.AppEntry
import com.grayvines.runway.system.apps.LabelOrder
import kotlin.math.roundToInt

const val DRAWER_INDEX_TAG = "drawer-index"

/** The strip of letters is this wide; the list keeps clear of it. */
val INDEX_WIDTH = 28.dp

private const val LETTER_ALPHA = 0.6f

/** Above and below each letter: enough to land a finger on, not so much the strip goes sparse. */
private val LETTER_PADDING = 3.dp

/** A letter of the index and where in the list the apps under it begin. */
class IndexEntry(val letter: Char, val position: Int)

/** The letters the drawer's apps start with, in list order, each with its first app's position. */
fun List<AppEntry>.index(): List<IndexEntry> = index { it.label }

/**
 * Each letter once, at its first app, for a list in [LabelOrder]: accented initials file under
 * their plain letter, and everything that is not a letter comes first, under one '#'.
 */
internal fun <T> List<T>.index(label: (T) -> String): List<IndexEntry> {
    val entries = mutableListOf<IndexEntry>()
    forEachIndexed { position, item ->
        val letter = LabelOrder.initial(label(item))
        if (entries.lastOrNull()?.letter != letter) entries += IndexEntry(letter, position)
    }
    return entries
}

/** Which entry a finger at [y] of [height] is on: the strip is divided evenly between them. */
internal fun List<IndexEntry>.under(y: Float, height: Float): IndexEntry? {
    val validBounds = y.isFinite() && height.isFinite() && height > 0f
    if (isEmpty() || !validBounds) return null
    val slot = (y / height * size).toInt().coerceIn(0, size - 1)
    return this[slot]
}

/**
 * The letters down the right edge. A touch or a drag along them jumps the list to that letter's
 * first app ([onJump] with its position) and holds the letter bright while the finger is on it.
 */
@Composable
fun DrawerIndex(entries: List<IndexEntry>, onJump: (position: Int) -> Unit, modifier: Modifier) {
    val jump = rememberUpdatedState(onJump)
    val current = rememberUpdatedState(entries)
    var held by remember { mutableStateOf<Char?>(null) }
    fun touch(y: Float, height: Float) {
        val entry = current.value.under(y, height) ?: return
        if (entry.letter != held) {
            held = entry.letter
            jump.value(entry.position)
        }
    }
    IndexStrip(
        modifier =
            modifier.width(INDEX_WIDTH).testTag(DRAWER_INDEX_TAG).pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    try {
                        touch(down.position.y, size.height.toFloat())
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            change.consume()
                            touch(change.position.y, size.height.toFloat())
                        }
                    } finally {
                        held = null
                    }
                }
            }
    ) {
        entries.forEach { entry ->
            val bright = entry.letter == held
            Text(
                entry.letter.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = if (bright) 1f else LETTER_ALPHA),
                modifier =
                    Modifier.clearAndSetSemantics {
                        contentDescription = "Jump to ${entry.letter}"
                        role = Role.Button
                        onClick {
                            jump.value(entry.position)
                            true
                        }
                    },
            )
        }
    }
}

/**
 * Stacks the letters with [LETTER_PADDING] above and below each when they fit, or distributes them
 * evenly across the available height when space is tight (e.g. above the keyboard) so every letter
 * stays reachable and aligned with [under].
 */
@Composable
private fun IndexStrip(modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val width = constraints.constrainWidth(INDEX_WIDTH.roundToPx())
        if (measurables.isEmpty()) {
            layout(width, constraints.constrainHeight(0)) {}
        } else {
            val loose =
                constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity)
            val placeables = measurables.map { it.measure(loose) }
            val padPx = LETTER_PADDING.roundToPx()
            val maxGlyphHeight = placeables.maxOf { it.height }
            val naturalHeight = (maxGlyphHeight + padPx * 2) * placeables.size
            val totalHeight = constraints.constrainHeight(naturalHeight)
            layout(width, totalHeight) {
                if (totalHeight >= maxGlyphHeight) {
                    val step = totalHeight.toFloat() / placeables.size
                    placeables.forEachIndexed { index, placeable ->
                        val maxY = (totalHeight - placeable.height).coerceAtLeast(0)
                        val y =
                            (index * step + (step - placeable.height) / 2f)
                                .roundToInt()
                                .coerceIn(0, maxY)
                        placeable.placeRelative((width - placeable.width) / 2, y)
                    }
                }
            }
        }
    }
}
