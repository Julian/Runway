package com.grayvines.runway.ui.menu

import android.util.Log
import androidx.sqlite.SQLiteException
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.drag.Bounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The home menu's state and what its actions do. A long press on empty home space or a tap on the
 * search bar's three dots opens it; a tap elsewhere, an action, HOME or anything else opening
 * closes it. [awaitPages] returns once the screen knows about at least that many home pages, so a
 * new page can be scrolled to.
 */
class HomeMenuHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    private val awaitPages: suspend (count: Int) -> Unit,
) {
    private val _state = MutableStateFlow<Bounds?>(null)

    /** What the menu is open beside (root px), or null. */
    val state: StateFlow<Bounds?> = _state

    private val _showPage = MutableSharedFlow<Int>(extraBufferCapacity = 1)

    /** The index of a page just added, for the pager to scroll to. */
    val showPage: SharedFlow<Int> = _showPage

    val isOpen: Boolean
        get() = _state.value != null

    /** The menu's actions, each closing it; [openSettings] is the activity's to provide. */
    fun actions(openSettings: () -> Unit) =
        HomeMenuActions(
            wallpaper = { closing { graph.wallpapers.pick() } },
            addPage = { closing { scope.launch { addPage() } } },
            settings = { closing(openSettings) },
        )

    fun open(beside: Bounds) {
        _state.value = beside
    }

    fun dismiss() {
        _state.value = null
    }

    /** A new page after the last, shown as soon as the screen has it. */
    private suspend fun addPage() {
        try {
            val count = graph.workspace.observe(Container.HOME).first().pages.size
            graph.workspace.addPage(Container.HOME, count)
            awaitPages(count + 1)
            _showPage.tryEmit(count)
        } catch (e: SQLiteException) {
            Log.e(TAG, "could not add a page", e)
        }
    }

    private inline fun closing(block: () -> Unit) {
        if (_state.value == null) return
        _state.value = null
        block()
    }

    private companion object {
        const val TAG = "Runway"
    }
}
