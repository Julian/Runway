package com.grayvines.runway.ui.folder

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.grayvines.runway.system.apps.AppEntry
import kotlinx.coroutines.launch

/** How much bigger the tile a finger has hold of is drawn, so it reads as picked up. */
private const val HELD_SCALE = 1.1f

/**
 * A folder's apps as its sheet shows them: the folder's own order, or the one a finger is
 * rearranging. A hold on a tile picks it up; carrying it over another tile takes that one's place
 * there and then, and the release writes the order the folder was left in.
 *
 * Slots are the grid's own geometry and stay where they are; what a carry changes is which app is
 * in which slot. So the tile under the finger is drawn at its slot plus how far the finger has
 * carried it, measured from the slot it was picked up in, which stays true across every change of
 * places.
 */
@Stable
internal class FolderOrder {
    /** The apps in the order shown, which is the folder's own unless a finger is rearranging. */
    var shown by mutableStateOf<List<AppEntry>>(emptyList())
        private set

    /** The key of the app a finger has hold of; null when none. */
    var held by mutableStateOf<String?>(null)
        private set

    /** Where the finger was when it took hold, in the grid's pixels. */
    private var grabbed = Offset.Zero

    /** How far the finger has moved since. */
    private var carried by mutableStateOf(Offset.Zero)

    /** The slot the held tile was picked up in, and the slot it sits in now. */
    private var takenFrom = IntOffset.Zero
    private var slot by mutableStateOf(IntOffset.Zero)

    /** From a release until the folder's own order has caught up with the one written. */
    private var written = false

    /** How much of the carry is still drawn: 1 under the finger, 0 once the tile has landed. */
    private var landing by mutableStateOf(1f)

    /** Which hold this is, so a settle that began with the last one does not end this one. */
    private var gesture = 0

    /**
     * Takes up the folder's own order, unless a finger is rearranging it or an order it left is
     * still on its way to the store.
     */
    fun follow(apps: List<AppEntry>) {
        if (held != null) return
        if (written && apps.keys() != shown.keys()) return
        written = false
        shown = apps
    }

    /** A hold at [at], the grid's pixels: takes up the app there, if the hold was on one. */
    fun take(at: Offset, grid: LazyGridState) {
        val item = grid.itemAt(at, shown.indices) ?: return
        takenFrom = item.offset
        slot = item.offset
        grabbed = at
        carried = Offset.Zero
        landing = 1f
        gesture++
        held = shown[item.index].key
    }

    /**
     * The finger has moved by [delta]. Once it is over another app's slot, the held app takes that
     * slot and the apps between shuffle up or down to make room.
     */
    fun carry(delta: Offset, grid: LazyGridState) {
        val key = held ?: return
        carried += delta
        val from = shown.indexOfFirst { it.key == key }
        val over = grid.itemAt(grabbed + carried, shown.indices)
        if (from >= 0 && over != null && over.index != from) {
            shown = shown.toMutableList().apply { add(over.index, removeAt(from)) }
            slot = over.offset
        }
    }

    /** Where the held tile is drawn from its slot: how far it is still carried, as it lands. */
    fun carriedFromSlot(): Offset = (carried + (takenFrom - slot).toOffset()) * landing

    /** How much bigger the held tile is drawn, back to its own size as it lands. */
    fun heldScale(): Float = 1f + (HELD_SCALE - 1f) * landing

    /** The finger is up: the order shown is the folder's own from now on, once the write lands. */
    fun dropped(): List<AppEntry> {
        written = true
        return shown
    }

    /** The tile springs from under the finger into the slot it was left in, and is a tile again. */
    suspend fun land() {
        val mine = gesture
        animate(1f, 0f, animationSpec = spring(stiffness = Spring.StiffnessMedium)) { v, _ ->
            landing = v
        }
        if (gesture == mine) {
            held = null
            landing = 1f
        }
    }

    /** The gesture was taken away mid-carry; the folder's own order comes back. */
    fun letGo() {
        gesture++
        held = null
        landing = 1f
        written = false
    }

    private fun List<AppEntry>.keys() = map { it.key }
}

/**
 * The apps to draw, in the order to draw them: [apps] as the folder holds them, except while a
 * finger is rearranging them.
 */
@Composable
internal fun rememberFolderOrder(apps: List<AppEntry>): FolderOrder {
    val order = remember { FolderOrder() }
    LaunchedEffect(apps, order.held) { order.follow(apps) }
    return order
}

/**
 * Lets a finger rearrange the folder's apps within this grid: a hold picks one up, moving carries
 * it, and the release reports the order with [onDropped]. The gesture belongs to the grid, not to
 * the tiles: a tile carries the finger's offset in a layer of its own, which would take that same
 * offset back off the finger were the two on one node.
 */
@Composable
internal fun Modifier.rearranges(
    order: FolderOrder,
    grid: LazyGridState,
    onDropped: (List<AppEntry>) -> Unit,
): Modifier {
    // The gesture coroutine outlives recompositions and must always report to the current sheet.
    val dropped by rememberUpdatedState(onDropped)
    val scope = rememberCoroutineScope()
    return pointerInput(Unit) {
        detectDragGesturesAfterLongPress(
            onDragStart = { at -> order.take(at, grid) },
            onDrag = { change, delta ->
                change.consume()
                order.carry(delta, grid)
            },
            onDragEnd = {
                if (order.held != null) {
                    dropped(order.dropped())
                    scope.launch { order.land() }
                }
            },
            onDragCancel = { order.letGo() },
        )
    }
}

/** Draws this tile as the folder's order has it: the held one over the others, and bigger. */
internal fun Modifier.slotted(app: AppEntry, order: FolderOrder): Modifier =
    zIndex(if (order.held == app.key) 1f else 0f).graphicsLayer {
        // Read as the frame is drawn, not as it is composed: a carry costs no recomposition.
        if (order.held == app.key) {
            val carried = order.carriedFromSlot()
            translationX = carried.x
            translationY = carried.y
            scaleX = order.heldScale()
            scaleY = order.heldScale()
        }
    }

/** The item whose slot holds the grid point [p], if it is one of [among]; else null. */
private fun LazyGridState.itemAt(p: Offset, among: IntRange) =
    layoutInfo.visibleItemsInfo.firstOrNull { it.index in among && it.holds(p) }

private fun LazyGridItemInfo.holds(p: Offset) =
    p.x >= offset.x &&
        p.x < offset.x + size.width &&
        p.y >= offset.y &&
        p.y < offset.y + size.height

private fun IntOffset.toOffset() = Offset(x.toFloat(), y.toFloat())
