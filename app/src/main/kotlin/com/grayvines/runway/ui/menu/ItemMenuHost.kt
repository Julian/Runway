package com.grayvines.runway.ui.menu

import android.util.Log
import androidx.sqlite.SQLiteException
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.Container
import com.grayvines.runway.data.WorkspaceRepository
import com.grayvines.runway.data.addToDrawerFolder
import com.grayvines.runway.data.createDrawerFolder
import com.grayvines.runway.data.deleteFolder
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.home.HomeItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The item menu's state and what its actions do; one long press opens it, anything else closes it.
 */
class ItemMenuHost(
    private val graph: AppGraph,
    private val scope: CoroutineScope,
    /** Whether a menu may open now: not over a live drag, where a second finger would put it. */
    private val mayOpen: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow<ItemMenuState?>(null)

    /** The menu a long press opened, until it is dismissed, acted on, or turned into a drag. */
    val state: StateFlow<ItemMenuState?> = _state

    val isOpen: Boolean
        get() = _state.value != null

    /** The menu's actions, each closing it. */
    val actions =
        ItemMenuActions(
            appInfo = { withItem { it.app?.let(graph.appRepository::showAppInfo) } },
            uninstall = { withItem { it.app?.let(graph.appRepository::uninstall) } },
            remove = { withItem { item -> write("remove the item") { removeItem(item.id) } } },
            newFolder = {
                withItem { item ->
                    item.app?.let { app -> write("make a folder") { createDrawerFolder(app.ref) } }
                }
            },
            addToFolder = { folderId ->
                withItem { item ->
                    item.app?.let { app ->
                        write("add to the folder") { addToDrawerFolder(folderId, app.ref) }
                    }
                }
            },
            deleteFolder = {
                withItem { item ->
                    item.folderId?.let { id -> write("delete the folder") { deleteFolder(id) } }
                }
            },
        )

    fun hold(item: HomeItem, container: Container, page: Int, cell: Bounds) {
        if (mayOpen()) _state.value = ItemMenuState(item, container, page, cell)
    }

    fun dismiss() {
        _state.value = null
    }

    /** A layout write off the main thread; a failure is logged, and the launcher stays up. */
    private fun write(what: String, block: suspend WorkspaceRepository.() -> Unit) {
        scope.launch {
            try {
                graph.workspace.block()
            } catch (e: SQLiteException) {
                Log.e(TAG, "could not $what", e)
            }
        }
    }

    private inline fun withItem(block: (HomeItem) -> Unit) {
        val menu = _state.value ?: return
        _state.value = null
        block(menu.item)
    }

    private companion object {
        const val TAG = "Runway"
    }
}
