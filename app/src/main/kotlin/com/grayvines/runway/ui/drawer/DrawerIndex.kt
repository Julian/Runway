package com.grayvines.runway.ui.drawer

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.grayvines.runway.system.apps.AppEntry

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

/** Anything not a letter files under '#'. Letters are compared in upper case. */
internal fun <T> List<T>.index(label: (T) -> String): List<IndexEntry> {
    val entries = mutableListOf<IndexEntry>()
    forEachIndexed { position, item ->
        val letter = label(item).firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() } ?: '#'
        if (entries.lastOrNull()?.letter != letter) entries += IndexEntry(letter, position)
    }
    return entries
}

/** Which entry a finger at [y] of [height] is on: the strip is divided evenly between them. */
internal fun List<IndexEntry>.under(y: Float, height: Float): IndexEntry? {
    if (isEmpty()) return null
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
    Column(
        modifier =
            modifier
                .width(INDEX_WIDTH)
                .testTag(DRAWER_INDEX_TAG)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            touch(it.y, size.height.toFloat())
                            tryAwaitRelease()
                            held = null
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { held = null },
                        onDragCancel = { held = null },
                    ) { change, _ ->
                        touch(change.position.y, size.height.toFloat())
                    }
                },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        entries.forEach { entry ->
            val bright = entry.letter == held
            Text(
                entry.letter.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White.copy(alpha = if (bright) 1f else LETTER_ALPHA),
                modifier =
                    Modifier.padding(vertical = LETTER_PADDING).semantics {
                        contentDescription = "Jump to ${entry.letter}"
                    },
            )
        }
    }
}
