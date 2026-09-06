package com.grayvines.runway.ui.menu

import android.util.Log
import androidx.sqlite.SQLiteException
import com.grayvines.runway.AppGraph
import com.grayvines.runway.data.Container
import com.grayvines.runway.ui.drag.Bounds
import com.grayvines.runway.ui.home.HomeItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The item menu's state and what its actions do; one long press opens it, anything else closes it.
 */
class ItemMenuHost(private val graph: AppGraph, private val scope: CoroutineScope) {
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
            remove = {
                withItem { item ->
                    scope.launch {
                        try {
                            graph.workspace.removeItem(item.id)
                        } catch (e: SQLiteException) {
                            Log.e(TAG, "could not remove the item", e)
                        }
                    }
                }
            },
        )

    fun hold(item: HomeItem, container: Container, page: Int, cell: Bounds) {
        _state.value = ItemMenuState(item, container, page, cell)
    }

    fun dismiss() {
        _state.value = null
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
